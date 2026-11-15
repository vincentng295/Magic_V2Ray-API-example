package com.example.magicv2raydeeplink;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import java.nio.charset.StandardCharsets;

/**
 * Talks to Magic V2Ray. Two ways, same result: Magic V2Ray shows a confirmation (who asks, the full uri,
 * the apps kept out of the VPN) and then routes the device through the given node until it is disconnected.
 *
 *  - explicit intent (connectByIntent): Android tells Magic V2Ray which app asked, so the user can choose
 *    "Always trust this app" and later connects need no prompt;
 *  - deep link (connectByLink): works for websites too, but is always confirmed.
 *
 * Both also carry {@link DnsOverrides#JSON} as the {@code overrides} parameter: while connected, Hijack DNS is off
 * and the routing rules are replaced by "port 53 -> proxy, everything else direct". A request with overrides is
 * always confirmed, even for an app marked "Always trust", and the dialog lists what is changed.
 */
final class MagicV2Ray {

    static final String PKG = "com.huskydg.magicv2ray";
    static final String ACTION_CONNECT = "com.huskydg.magicv2ray.action.CONNECT";
    static final String ACTION_DISCONNECT = "com.huskydg.magicv2ray.action.DISCONNECT";

    private MagicV2Ray() {
    }

    /** True if Magic V2Ray is installed and new enough to know the connect API. */
    static boolean available(Activity a) {
        Intent i = new Intent(ACTION_CONNECT).setPackage(PKG);
        return i.resolveActivity(a.getPackageManager()) != null;
    }

    static boolean connectByIntent(Activity a, String uri, String name) {
        Intent i = new Intent(ACTION_CONNECT)
                .setPackage(PKG)
                .putExtra("uri", uri)
                .putExtra("name", name)
                // This app serves the SOCKS5 port and reaches the internet through the device: it must stay
                // OUT of the VPN, or its own traffic would loop back into itself.
                .putExtra("exclude", new String[]{a.getPackageName()})
                // Intent / broadcast: the extra is the JSON text itself.
                .putExtra("overrides", DnsOverrides.JSON);
        return start(a, i);
    }

    static boolean connectByLink(Activity a, String uri, String name) {
        Uri link = new Uri.Builder().scheme("magicv2ray").authority("connect")
                .appendQueryParameter("uri", uri)             // appendQueryParameter URL-encodes the value
                .appendQueryParameter("name", name)
                .appendQueryParameter("exclude", a.getPackageName())
                // Deep link: base64url (no padding, no wrapping) of the JSON.
                .appendQueryParameter("overrides", Base64.encodeToString(
                        DnsOverrides.JSON.getBytes(StandardCharsets.UTF_8),
                        Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING))
                .build();
        return start(a, new Intent(Intent.ACTION_VIEW, link).setPackage(PKG));
    }

    static boolean disconnect(Activity a) {
        return start(a, new Intent(ACTION_DISCONNECT).setPackage(PKG));
    }

    private static boolean start(Activity a, Intent i) {
        try {
            a.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }
}
