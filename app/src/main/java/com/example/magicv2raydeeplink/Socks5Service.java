package com.example.magicv2raydeeplink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.IOException;
import java.util.ArrayDeque;

/** Keeps the SOCKS5 server alive in the background (a foreground service, so Android does not kill it). */
public class Socks5Service extends Service {

    static final int PORT = 5353;
    static final String ACTION_STOP = "com.example.magicv2raydeeplink.STOP";
    private static final String CHANNEL = "socks";
    private static final int MAX_LINES = 200;

    /** True while the server accepts connections. */
    static volatile boolean listening;
    /** Set by the activity: called (from any thread) when the log or the state changed. */
    static volatile Runnable onChange;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();

    private Socks5Server server;

    static synchronized void log(String line) {
        LINES.addLast(line);
        while (LINES.size() > MAX_LINES) LINES.removeFirst();
        notifyChange();
    }

    static synchronized String logText() {
        return String.join("\n", LINES);
    }

    private static void notifyChange() {
        Runnable r = onChange;
        if (r != null) r.run();
    }

    static void start(Context c) {
        Intent i = new Intent(c, Socks5Service.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        c.startService(new Intent(c, Socks5Service.class).setAction(ACTION_STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showForeground();
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (server == null) {
            try {
                server = new Socks5Server(PORT, Socks5Service::log);
                server.start();
                listening = true;
                log("SOCKS5 listening on 127.0.0.1:" + PORT + ", DNS (port 53) goes to " + Socks5Server.DNS_TARGET);
            } catch (IOException e) {
                server = null;
                log("cannot listen on 127.0.0.1:" + PORT + ": " + e.getMessage());
                stopForeground(true);
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        if (server != null) {
            server.stop();
            server = null;
            log("SOCKS5 stopped");
        }
        listening = false;
        notifyChange();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "SOCKS5 proxy", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setContentTitle("SOCKS5 on 127.0.0.1:" + PORT)
                .setContentText("DNS (port 53) is sent to " + Socks5Server.DNS_TARGET)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }
}
