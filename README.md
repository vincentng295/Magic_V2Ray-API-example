# magic-dns-rewrite

An example Android app for the **connect API of [Magic V2Ray](https://github.com/vincentng295/Magic_V2Ray)**.

It runs a tiny local SOCKS5 proxy, asks Magic V2Ray to route **only the device's DNS** through it, and rewrites every
DNS query on the way: **anything sent to port 53 goes to `9.9.9.9`** (Quad9), whatever the app originally asked.
To do that it uses the connect API's temporary **`overrides`**: Hijack DNS off, and the routing rules replaced by
*all port 53 → proxy, everything else direct*.

```
 any app ─ DNS (port 53) ─► Magic V2Ray (VPN) ──► socks://127.0.0.1:5353 ──► magic-dns-rewrite ──► 9.9.9.9:53
         └ everything else ► Magic V2Ray (VPN) ──► direct (never touches this app)
```

It is deliberately small, so you can copy the parts you need: [`MagicV2Ray.java`](app/src/main/java/com/example/magicv2raydeeplink/MagicV2Ray.java)
is the whole client side of the API, [`Socks5Server.java`](app/src/main/java/com/example/magicv2raydeeplink/Socks5Server.java)
is a dependency-free SOCKS5 server (CONNECT + UDP ASSOCIATE).

## How it works

1. **Proxy**: a foreground service listens on `127.0.0.1:5353`.
   - TCP `CONNECT` is relayed out of the device; if the destination port is 53, the host is replaced with `9.9.9.9`.
   - UDP `ASSOCIATE` is relayed the same way. Every destination gets its own outgoing socket, and replies are
     wrapped with the **original** destination as their source, so the client never notices the rewrite.
2. **Connect**: once the port is really open, the app asks Magic V2Ray for `socks://127.0.0.1:5353`, passing its own
   package in `exclude` so its outgoing traffic stays *outside* the VPN. Without that, the proxy's own sockets would be
   captured by the tunnel and loop straight back into it.
3. Magic V2Ray shows a confirmation (who asks, the full URI, the apps kept out of the VPN). After you accept, the
   connection lasts until it is disconnected; your own nodes and settings are never changed, and disconnecting brings
   your previous node and settings back.
4. **Overrides**: the same request carries `overrides` (see below), so Magic V2Ray applies them to a *copy* of your
   settings for the length of the session. They are never saved.

### The overrides it sends

```json
{
  "traffic": { "hijackDns": false },
  "routing": {
    "mode": "replace",
    "rules": [
      { "remarks": "DNS (port 53) -> proxy", "port": "53",            "outboundTag": "proxy"  },
      { "remarks": "else direct (ip)",       "ip": "0.0.0.0/0,::/0",  "outboundTag": "direct" },
      { "remarks": "else direct (domain)",   "domain": "regexp:.+",    "outboundTag": "direct" }
    ]
  }
}
```

- **`traffic.hijackDns = false`**: the device's DNS queries are no longer answered by Xray's own DNS module, so they
  all travel as ordinary port-53 traffic and reach this proxy. (Before, you had to switch Hijack DNS off by hand.)
- **`routing` in mode `replace`**: your own rules are dropped for the session, then *port 53 → `proxy`* (the node
  `socks://127.0.0.1:5353`) and *everything else → `direct`*. "Else" takes two rules because Magic V2Ray drops a rule
  with no match condition (a bare catch-all), and an `ip` rule never matches a destination that still has a domain name.
  Without them the unmatched traffic would fall back to Magic V2Ray's default, which is `proxy`.
- Only whitelisted keys are accepted and `outboundTag` must be `proxy`, `direct` or `block`
  (`ExternalSession.sanitizeOverrides` in Magic V2Ray). The text lives in [`DnsOverrides.java`](app/src/main/java/com/example/magicv2raydeeplink/DnsOverrides.java).
- Intent: the extra `overrides` is the JSON text. Deep link: `overrides=<base64url of the JSON, no padding>`.

### Two ways to call Magic V2Ray

| Button | Call | Notes |
|---|---|---|
| Connect (intent) | `startActivity` with action `com.huskydg.magicv2ray.action.CONNECT` and extras `uri`, `name`, `exclude`, `overrides` | Android tells Magic V2Ray which app called it. A request **with overrides is always confirmed**, even for an app marked *Always trust* |
| Connect (deep link) | `magicv2ray://connect?uri=<urlencoded>&name=<name>&exclude=<package>&overrides=<base64url>` | Works for websites too, but is always confirmed (a link can come from anywhere) |

Disconnecting is `com.huskydg.magicv2ray.action.DISCONNECT` or `magicv2ray://disconnect`. The app sends it
*before* stopping the proxy, because stopping the proxy first would cut your connection.

## Knowing when Magic V2Ray disconnects

The proxy is only useful while Magic V2Ray routes through it. Magic V2Ray can end the connection without this app doing
anything (the user taps Disconnect there or in the Quick Settings tile, the engine crashes, ...), so the app uses the
**info / state-event API** (Magic V2Ray API level 1):

1. **Info**: `MagicV2Ray.info()` asks the read-only provider `content://com.huskydg.magicv2ray.api` (`getInfo`) for the
   version, the API level, the supported features and the current state. The main screen shows it, and the app warns
   when `state_events` is missing (an older Magic V2Ray still connects but cannot say when it disconnects).
2. **State events**: while the proxy runs, `Socks5Service` holds a listener (`MagicV2Ray.listen()`) for the broadcast
   `com.huskydg.magicv2ray.event.STATE_CHANGED`. When it reports `connected = false` after this app's session was up,
   the service stops the proxy by itself. The session is recognised by the caller package (explicit intent) or by the
   session name (deep link, caller unknown).

```
 Magic V2Ray ── STATE_CHANGED (connected=false, reason=session_ended) ──► Socks5Service ──► stops the proxy
```

Both calls need `<queries><package android:name="com.huskydg.magicv2ray"/></queries>` (already in the manifest), must not
run on the main thread (`info`, `state`), and the events can arrive twice (`listen()` drops the copy). If Magic V2Ray
itself is killed in non-root mode nothing can be sent: call `MagicV2Ray.state()` when that matters. The full description
of every call, extra and rule is the comment at the top of [`MagicV2Ray.java`](app/src/main/java/com/example/magicv2raydeeplink/MagicV2Ray.java),
written so that another developer, or an AI assistant, can reuse the client in a different app.

## Usage

1. Install Magic V2Ray (a version with the connect API; API level 1 for info and disconnect events) and this app.
2. Open this app and tap **Start proxy + connect (intent)**.
3. Confirm in Magic V2Ray. The dialog lists the overrides (*Traffic settings: 1*, *Routing rules: 3*). DNS lines such
   as `DNS/udp  1.1.1.1:53  ->  9.9.9.9:53` appear in the log.
4. Tap **Disconnect + stop proxy** when you are done. If you disconnect inside Magic V2Ray instead, the proxy stops by itself.

### Things to know

- **A Magic V2Ray with `overrides`.** An older build ignores the extra and connects with its own settings (Hijack DNS
  on, your rules): the proxy then only sees Xray's upstream lookups, not every query. Update Magic V2Ray.
- **Non-DNS traffic bypasses the proxy.** Because of the routing override, only port 53 reaches this app. Everything
  else leaves the VPN as `direct`, so your own node and rules are not used while this session runs.
- **Professional mode** in Magic V2Ray owns the hand-written config, so it refuses a session with overrides.
- **Only port 53.** DNS over TLS (853) and over HTTPS (443) are not touched.
- **No authentication**, but the server only listens on loopback.
- **UDP limits.** Fragmented datagrams (`FRAG != 0`) are dropped. A UDP flow closes after 60 s of silence, at most 256 at a time.
- **Android 11+ package visibility.** The manifest declares `<queries>` for Magic V2Ray; without it `startActivity` cannot see the app.

## Build

```sh
./build.sh --test    # host tests (SOCKS5 server, overrides JSON): needs a JDK only
./build.sh           # tests + release APK in dist/ (needs the Android SDK and Gradle 8.7+, or ./gradlew)
./build.sh --debug   # debug APK
```

The project has no Gradle wrapper; open it in Android Studio, or run `gradle wrapper` once to create one.

**Signing.** Set `EX_KEYSTORE`, `EX_KEYSTORE_PASSWORD`, `EX_KEY_ALIAS` and `EX_KEY_PASSWORD` to sign with your own key;
otherwise the debug key is used so the APK still installs.

**CI** (`.github/workflows/ci.yml`): shellcheck → host tests → build APK → upload artifact. Pushing a tag `v*` publishes a
release. Optional secrets: `EX_KEYSTORE_BASE64`, `EX_KEYSTORE_PASSWORD`, `EX_KEY_ALIAS`, `EX_KEY_PASSWORD`; without them
the APK is signed with the debug key.

## Layout

| File | What it does |
|---|---|
| `MagicV2Ray.java` | The whole client of the API: connect (with `overrides`) / disconnect, info, state, state-event listener; documented for reuse |
| `DnsOverrides.java` | The `overrides` JSON: Hijack DNS off, port 53 → proxy, else direct; no Android dependency |
| `Socks5Server.java` | SOCKS5 (CONNECT + UDP ASSOCIATE), port 53 → `9.9.9.9`; no Android dependency |
| `Socks5Service.java` | Foreground service that keeps the server alive, buffers the log and stops itself when Magic V2Ray disconnects |
| `MainActivity.java` | Shows Magic V2Ray's info, starts the server, waits for the port to open, then calls Magic V2Ray with the overrides |
| `tests/Socks5ServerTest.java` | Host-side tests of the proxy, run by `./build.sh --test` and by CI |
| `tests/DnsOverridesTest.java` | Host-side check of the overrides JSON (balanced, rule order, allowed tags) |

## License

Released into the public domain as an example: copy whatever you need.
