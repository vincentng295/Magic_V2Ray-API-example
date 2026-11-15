# magic-dns-rewrite

An example Android app for the **connect API of [Magic V2Ray](https://github.com/vincentng295/Magic_V2Ray)**.

It runs a tiny local SOCKS5 proxy, asks Magic V2Ray to route the whole device through it, and rewrites every
DNS query on the way: **anything sent to port 53 goes to `9.9.9.9`** (Quad9), whatever the app originally asked.

```
 any app ──► Magic V2Ray (VPN) ──► socks://127.0.0.1:5353 ──► magic-dns-rewrite ──► internet
                                                   port 53 ─► 9.9.9.9:53
                                                   the rest ─► unchanged
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
   connection lasts until it is disconnected; your own nodes are never changed, and disconnecting brings your
   previous node back.

### Two ways to call Magic V2Ray

| Button | Call | Notes |
|---|---|---|
| Connect (intent) | `startActivity` with action `com.huskydg.magicv2ray.action.CONNECT` and extras `uri`, `name`, `exclude` | Android tells Magic V2Ray which app called, so you can tick **Always trust this app**; later connects need no prompt |
| Connect (deep link) | `magicv2ray://connect?uri=<urlencoded>&name=<name>&exclude=<package>` | Works for websites too, but is always confirmed (a link can come from anywhere) |

Disconnecting is `com.huskydg.magicv2ray.action.DISCONNECT` or `magicv2ray://disconnect`. The app sends it
*before* stopping the proxy, because stopping the proxy first would cut your connection.

## Usage

1. Install Magic V2Ray (a version with the connect API) and this app.
2. Open this app and tap **Start proxy + connect (intent)**.
3. Confirm in Magic V2Ray. DNS lines such as `DNS/udp  1.1.1.1:53  ->  9.9.9.9:53` appear in the log.
4. Tap **Disconnect + stop proxy** when you are done.

### Things to know

- **Hijack DNS.** Magic V2Ray has *Traffic settings → DNS → Hijack DNS*, on by default: the device's DNS queries are
  then answered by Xray's own DNS module, and only Xray's upstream lookups reach this proxy. To send **every**
  query through the app (and have it rewritten), turn Hijack DNS off.
- **Only port 53.** DNS over TLS (853) and over HTTPS (443) are not touched.
- **No authentication**, but the server only listens on loopback.
- **UDP limits.** Fragmented datagrams (`FRAG != 0`) are dropped. A UDP flow closes after 60 s of silence, at most 256 at a time.
- **Android 11+ package visibility.** The manifest declares `<queries>` for Magic V2Ray; without it `startActivity` cannot see the app.

## Build

```sh
./build.sh --test    # host tests of the SOCKS5 server: needs a JDK only
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
| `MagicV2Ray.java` | Connect / disconnect through an intent or a deep link |
| `Socks5Server.java` | SOCKS5 (CONNECT + UDP ASSOCIATE), port 53 → `9.9.9.9`; no Android dependency |
| `Socks5Service.java` | Foreground service that keeps the server alive and buffers the log |
| `MainActivity.java` | Starts the server, waits for the port to open, then calls Magic V2Ray |
| `tests/Socks5ServerTest.java` | Host-side tests, run by `./build.sh --test` and by CI |

## License

Released into the public domain as an example: copy whatever you need.
