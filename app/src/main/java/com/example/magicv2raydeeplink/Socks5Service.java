package com.example.magicv2raydeeplink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.IOException;
import java.util.ArrayDeque;

/**
 * Keeps the SOCKS5 server alive in the background (a foreground service, so Android does not kill it).
 *
 * <p>It is also where this app learns that Magic V2Ray DISCONNECTED: while the server runs, a state listener
 * ({@link MagicV2Ray#listen}) is registered. When Magic V2Ray reports that the session of this app is over (the user
 * pressed Disconnect in Magic V2Ray, the tile, a crash ...) nobody needs the proxy any more, so the service stops
 * itself. A service (not the activity) owns the listener because the activity is usually not on screen.
 */
public class Socks5Service extends Service {

    static final int PORT = 5353;
    /** Name of our session in Magic V2Ray: it is shown there and comes back as {@code sessionName} in state events. */
    static final String SESSION_NAME = "DNS rewrite example";
    static final String ACTION_STOP = "com.example.magicv2raydeeplink.STOP";
    private static final String CHANNEL = "socks";
    private static final int MAX_LINES = 200;

    /** True while the server accepts connections. */
    static volatile boolean listening;
    /** Set by the activity: called (from any thread) when the log or the state changed. */
    static volatile Runnable onChange;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();

    private Socks5Server server;
    /** Receiver of Magic V2Ray's state events, registered while the server runs. */
    private BroadcastReceiver magicReceiver;
    /** True once Magic V2Ray told us that a session of THIS app is connected. A disconnect only matters after that. */
    private boolean sessionUp;

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
                magicReceiver = MagicV2Ray.listen(this, this::onMagicState);
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

    /**
     * Called (main thread) for every connect / disconnect of Magic V2Ray. See {@link MagicV2Ray.StateEvent}.
     * We only care about OUR session: it is ours when Magic V2Ray names this package as the caller (explicit intent)
     * or, for a deep link where the caller is unknown, when the session name is the one we asked for.
     */
    private void onMagicState(MagicV2Ray.StateEvent e) {
        boolean ours = e.session && (getPackageName().equals(e.caller) || SESSION_NAME.equals(e.sessionName));
        log("Magic V2Ray: " + e);
        if (e.connected) {
            if (ours) sessionUp = true;             // our connect was accepted: from now on a disconnect ends us
        } else if (sessionUp) {
            // Magic V2Ray is no longer routing through us (session ended, user stopped it, error, crash).
            // The proxy has no purpose any more: stop it and let the activity show the new state.
            log("Magic V2Ray disconnected (" + e.reason + "), stopping the proxy");
            sessionUp = false;
            shutdown();
            stopForeground(true);
            stopSelf();
        }
    }

    private void shutdown() {
        MagicV2Ray.unlisten(this, magicReceiver);
        magicReceiver = null;
        sessionUp = false;
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
