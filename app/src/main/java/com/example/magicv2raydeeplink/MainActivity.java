package com.example.magicv2raydeeplink;

import android.Manifest;
import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 1. starts a local SOCKS5 server on 127.0.0.1:5353 (it rewrites every port-53 destination to 9.9.9.9),
 * 2. asks Magic V2Ray to route the device through it: socks://127.0.0.1:5353, keeping this app out of the VPN,
 *    with temporary overrides (see DnsOverrides): Hijack DNS off, and routing "port 53 -> proxy, else direct".
 */
public class MainActivity extends Activity {

    private static final String NODE_URI = "socks://127.0.0.1:" + Socks5Service.PORT;
    private static final String NODE_NAME = Socks5Service.SESSION_NAME;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView state;
    private TextView log;
    private ScrollView scroll;
    /** What Magic V2Ray said about itself (version, API level, state); null until the first answer arrives. */
    private volatile MagicV2Ray.Info magic;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);

        state = new TextView(this);
        state.setTextSize(16);
        root.addView(state);

        root.addView(button("Start proxy + connect (intent)", () -> startAndConnect(false)));
        root.addView(button("Start proxy + connect (deep link)", () -> startAndConnect(true)));
        root.addView(button("Disconnect + stop proxy", this::disconnectAndStop));

        scroll = new ScrollView(this);
        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(12);
        log.setTextIsSelectable(true);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Socks5Service.onChange = () -> ui.post(this::render);
        render();
        refreshMagicInfo();
    }

    @Override
    protected void onPause() {
        Socks5Service.onChange = null;
        super.onPause();
    }

    private void render() {
        state.setText((Socks5Service.listening ? "● SOCKS5 on 127.0.0.1:" + Socks5Service.PORT : "○ proxy stopped")
                + "\nDNS (port 53) → " + Socks5Server.DNS_TARGET
                + "\nOverrides: Hijack DNS off · port 53 → proxy · else direct"
                + "\n" + (magic == null ? "Magic V2Ray: checking..." : magic.summary()));
        log.setText(Socks5Service.logText());
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    /**
     * Asks Magic V2Ray who it is (MagicV2Ray.info). The call can take a moment (root mode asks the root service),
     * so it runs on its own thread and the result is shown when it arrives.
     */
    private void refreshMagicInfo() {
        new Thread(() -> {
            magic = MagicV2Ray.info(getApplicationContext());
            ui.post(this::render);
        }, "magic-info").start();
    }

    private void startAndConnect(final boolean deepLink) {
        if (!MagicV2Ray.available(this)) {
            Toast.makeText(this, "Magic V2Ray with the connect API is not installed", Toast.LENGTH_LONG).show();
            return;
        }
        // Old Magic V2Ray (API level 0) still connects, but cannot tell us when it disconnects:
        // the proxy then keeps running until the user taps "Disconnect + stop proxy".
        MagicV2Ray.Info m = magic;
        if (m != null && !m.has(MagicV2Ray.FEATURE_STATE_EVENTS)) {
            Toast.makeText(this, "This Magic V2Ray cannot report disconnects: stop the proxy by hand", Toast.LENGTH_LONG).show();
        }
        Socks5Service.start(this);
        // Connect only once the port is really open, or Magic V2Ray would start against a dead proxy.
        final long deadline = System.currentTimeMillis() + 3000;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (Socks5Service.listening) {
                    boolean ok = deepLink
                            ? MagicV2Ray.connectByLink(MainActivity.this, NODE_URI, NODE_NAME)
                            : MagicV2Ray.connectByIntent(MainActivity.this, NODE_URI, NODE_NAME);
                    if (!ok) Toast.makeText(MainActivity.this, "Could not open Magic V2Ray", Toast.LENGTH_LONG).show();
                } else if (System.currentTimeMillis() < deadline) {
                    ui.postDelayed(this, 100);
                } else {
                    Toast.makeText(MainActivity.this, "The proxy did not start, see the log", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void disconnectAndStop() {
        // Order matters: leave the proxy running until Magic V2Ray has let go of it.
        MagicV2Ray.disconnect(this);
        ui.postDelayed(() -> Socks5Service.stop(this), 1500);
    }

    private Button button(String text, Runnable action) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setOnClickListener(v -> action.run());
        return btn;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
