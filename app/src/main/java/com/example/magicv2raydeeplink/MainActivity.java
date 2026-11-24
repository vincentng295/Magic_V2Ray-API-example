package com.example.magicv2raydeeplink;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Asks Magic V2Ray (API level 2) for a temporary session:
 * <ol>
 *   <li>node: the one Magic V2Ray is connected to right now ({@code getActiveNode}, used as {@code nodeid://<id>});
 *       when nothing is connected, {@code freedom://localhost};</li>
 *   <li>settings (see {@link DnsOverrides}): Hijack DNS on, Proxy DNS = https://9.9.9.9/dns-query.</li>
 * </ol>
 * No local proxy is involved: the app only talks to Magic V2Ray.
 */
public class MainActivity extends Activity {

    private static final String SESSION_NAME = "Magic DNS Rewrite";
    private static final int REQ_NODE_ACCESS = 42;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();
    private TextView state;
    private TextView log;
    private ScrollView scroll;
    private BroadcastReceiver stateReceiver;
    /** What Magic V2Ray said about itself (version, API level, state); null until the first answer arrives. */
    private volatile MagicV2Ray.Info magic;
    /** The node this app asked for the last time (a nodeid:// or freedom://localhost), for the status text. */
    private volatile String lastUri = "";
    private boolean pendingDeepLink;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);

        state = new TextView(this);
        state.setTextSize(16);
        root.addView(state);

        root.addView(button("Connect (intent)", () -> connect(false, true)));
        root.addView(button("Connect (deep link)", () -> connect(true, true)));
        root.addView(button("Disconnect", this::disconnect));

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
    protected void onStart() {
        super.onStart();
        // Magic V2Ray can end the session without us (Disconnect in its app, the tile, a crash ...): it tells us here.
        stateReceiver = MagicV2Ray.listen(this, e -> {
            log("event: " + e);
            refreshMagicInfo();
        });
    }

    @Override
    protected void onStop() {
        MagicV2Ray.unlisten(this, stateReceiver);
        stateReceiver = null;
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        refreshMagicInfo();
    }

    private void render() {
        state.setText("Hijack DNS on · Proxy DNS " + DnsOverrides.PROXY_DNS
                + "\nNode: " + (lastUri.isEmpty() ? "(chosen when you connect)" : lastUri)
                + "\n" + (magic == null ? "Magic V2Ray: checking..." : magic.summary()));
        log.setText(logBuf.toString());
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void log(String line) {
        final String s = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + line + "\n";
        ui.post(() -> {
            logBuf.append(s);
            render();
        });
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

    /**
     * Picks the node, then sends the connect request.
     * @param allowAsk true the first time: if Magic V2Ray wants the user's permission to read nodes, ask for it once
     */
    private void connect(final boolean deepLink, final boolean allowAsk) {
        if (!MagicV2Ray.available(this)) {
            Toast.makeText(this, "Magic V2Ray with the connect API is not installed", Toast.LENGTH_LONG).show();
            return;
        }
        pendingDeepLink = deepLink;
        new Thread(() -> {
            final MagicV2Ray.Info info = MagicV2Ray.info(getApplicationContext());
            magic = info;
            if (info.apiLevel < 2 || !info.has(MagicV2Ray.FEATURE_NODES)) {
                log("Magic V2Ray is older than API level 2: cannot read the node in use");
            } else {
                MagicV2Ray.ActiveNode n = MagicV2Ray.activeNode(getApplicationContext());
                if (n.ok()) {
                    String uri = NodePicker.pick(n.connected, n.source, n.uri);
                    log("active node: " + (n.connected ? n.source + " \"" + n.name + "\"" : "not connected") + " -> " + uri);
                    ui.post(() -> send(deepLink, uri));
                    return;
                }
                if (n.permissionRequired() && allowAsk) {
                    log("Magic V2Ray asks you to allow this app to read your nodes");
                    ui.post(() -> {
                        if (!MagicV2Ray.requestNodeAccess(this, REQ_NODE_ACCESS)) connect(deepLink, false);
                    });
                    return;
                }
                log("cannot read the node in use (" + n.status + ")");
            }
            // No permission / old Magic V2Ray: the public state still says whether the user's own node is connected.
            String uri = NodePicker.fallback(info.stateKnown, info.connected, info.sessionActive);
            log("fallback -> " + (uri == null ? "no uri (the selected node is kept)" : uri));
            ui.post(() -> send(deepLink, uri));
        }, "magic-connect").start();
    }

    private void send(boolean deepLink, String uri) {
        lastUri = uri == null ? "(the selected node)" : uri;
        boolean ok = deepLink
                ? MagicV2Ray.connectByLink(this, uri, SESSION_NAME)
                : MagicV2Ray.connectByIntent(this, uri, SESSION_NAME);
        if (!ok) Toast.makeText(this, "Could not open Magic V2Ray", Toast.LENGTH_LONG).show();
        render();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_NODE_ACCESS) return;
        boolean granted = resultCode == RESULT_OK && data != null && data.getBooleanExtra("granted", true);
        log(granted ? "node access granted (" + (data == null ? "" : data.getStringExtra("level")) + ")" : "node access refused");
        connect(pendingDeepLink, false);        // granted: reads the node; refused: falls back
    }

    private void disconnect() {
        if (!MagicV2Ray.disconnect(this)) {
            Toast.makeText(this, "Could not open Magic V2Ray", Toast.LENGTH_LONG).show();
        }
        lastUri = "";
        render();
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
