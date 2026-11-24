# magic-dns-rewrite

An example Android app for the **connect API (level 2) of [Magic V2Ray](https://github.com/vincentng295/Magic_V2Ray)**.

It asks Magic V2Ray for a temporary session that

- keeps using **the node Magic V2Ray is connected to right now**, or **`freedom://localhost`** (the built-in "no proxy"
  node) when nothing is connected, and
- turns **Hijack DNS on** and sets the **Proxy DNS to `https://9.9.9.9/dns-query`** (Quad9 over HTTPS).

There is **no local proxy any more**: the old version ran a SOCKS5 server and rewrote port 53 to 9.9.9.9. Now Xray's own
DNS module does that job, and the app only talks to Magic V2Ray (it needs no permission and no network of its own).

```
 this app ── CONNECT(uri = nodeid://<id> | freedom://localhost, overrides) ──► Magic V2Ray
 this app ◄── getActiveNode / info / STATE_CHANGED ─────────────────────────── Magic V2Ray
```

It is deliberately small, so you can copy the parts you need: [`MagicV2Ray.java`](app/src/main/java/com/example/magicv2raydeeplink/MagicV2Ray.java)
is the whole client side of the API, [`NodePicker.java`](app/src/main/java/com/example/magicv2raydeeplink/NodePicker.java)
is the "which node?" decision (plain Java, unit-tested on the host).

## How it works

1. **Which node?** (API level 2) The app calls `getActiveNode`:
   - Magic V2Ray is connected through a node (the user's own pick, or one that another app set) and its uri is usable
     (`nodeid://<id>` at the default *anonymous* level) → that node is requested again, so traffic keeps using it;
   - not connected, the built-in no-proxy node, or a node the app may not see → `freedom://localhost`.
2. **Permission.** Reading nodes needs the user's OK. On the first connect `getActiveNode` answers
   `permission_required`; the app then starts `REQUEST_NODE_ACCESS` with `startActivityForResult` and asks again. The app
   asks for the *anonymous* level only: `nodeid://<id>` is enough to connect, no server or credentials are revealed.
   If the user refuses (or Magic V2Ray is older than level 2) the public state decides: connected through the user's own
   node → no `uri` is sent and the selected node is kept; anything else → `freedom://localhost`.
3. **Connect.** One request carries `uri`, `name` and `overrides`. Magic V2Ray shows a confirmation (who asks, the node,
   what changes). After you accept, the session lasts until it is disconnected; your own nodes and settings are never
   changed, and disconnecting brings your previous node and settings back.
4. **Overrides.** Magic V2Ray applies them to a *copy* of your settings for the length of the session. They are never saved.

### The overrides it sends

```json
{ "traffic": { "hijackDns": true, "foreignDns": "https://9.9.9.9/dns-query" } }
```

- **`traffic.hijackDns = true`**: the device's DNS queries are answered by Xray's DNS module.
- **`traffic.foreignDns`**: the *Proxy DNS* upstream (comma separated list, replaced for the session). Whether it is
  reached through the node or directly follows your *DNS via proxy* setting, which the app leaves alone.
- Nothing else is overridden: your routing rules, hosts, exclude list and other settings stay in force. (No `exclude`
  request parameter either: there is no local server whose traffic could loop back into the VPN.)
- Only whitelisted keys are accepted (`ExternalSession.sanitizeOverrides` in Magic V2Ray). The text lives in
  [`DnsOverrides.java`](app/src/main/java/com/example/magicv2raydeeplink/DnsOverrides.java).
- Intent: the extra `overrides` is the JSON text. Deep link: `overrides=<base64url of the JSON, no padding>`.

See full details of the API in [overrides_api.md](./overrides_api.md).

### Two ways to call Magic V2Ray

| Button | Call | Notes |
|---|---|---|
| Connect (intent) | `startActivity` with action `com.huskydg.magicv2ray.action.CONNECT` and extras `uri`, `name`, `overrides` | Android tells Magic V2Ray which app called it, so `nodeid://` works. A request **with overrides is always confirmed**, even for an app marked *Always trust* |
| Connect (deep link) | `magicv2ray://connect?uri=<urlencoded>&name=<name>&overrides=<base64url>` | Works for websites too, but is always confirmed and refuses `nodeid://`: the app then sends no `uri` and the selected node is kept |

Disconnecting is `com.huskydg.magicv2ray.action.DISCONNECT` or `magicv2ray://disconnect`.

## Knowing when Magic V2Ray disconnects

Magic V2Ray can end the session without this app doing anything (the user taps Disconnect there or in the Quick Settings
tile, the engine crashes, ...). While the screen is open the app listens (`MagicV2Ray.listen()`) for the broadcast
`com.huskydg.magicv2ray.event.STATE_CHANGED`, logs it and refreshes the info line. `MagicV2Ray.info()` asks the read-only
provider `content://com.huskydg.magicv2ray.api` (`getInfo`) for the version, the API level, the features and the state.

Calls to the provider (`info`, `state`, `activeNode`) must not run on the main thread, the events can arrive twice
(`listen()` drops the copy), and the manifest needs `<queries><package android:name="com.huskydg.magicv2ray"/></queries>`
(already there). If Magic V2Ray itself is killed in non-root mode nothing can be sent: call `MagicV2Ray.state()` when that
matters. The full description of every call, extra and rule is the comment at the top of
[`MagicV2Ray.java`](app/src/main/java/com/example/magicv2raydeeplink/MagicV2Ray.java), written so that another developer,
or an AI assistant, can reuse the client in a different app.

## Usage

1. Install a Magic V2Ray with API level 2 (`api.features` has `nodes` and `connect_freedom`) and this app.
2. Open this app and tap **Connect (intent)**. The first time, allow it to read your nodes in Magic V2Ray.
3. Confirm the session in Magic V2Ray. The log shows the choice, e.g. `active node: selected "My server" -> nodeid://k3x...`
   or `active node: not connected -> freedom://localhost`.
4. Tap **Disconnect** when you are done, or disconnect inside Magic V2Ray.

### Things to know

- **Level 2 for the node choice.** An older Magic V2Ray (level 0 or 1) cannot say which node is in use: the app falls back
  as described above. One without `overrides` ignores the extra and connects with its own settings.
- **`freedom://localhost`** is Xray as a pure router: nothing goes through a proxy, your routing rules still apply, and the
  DoH queries to 9.9.9.9 leave the device directly.
- **A node set by another app's session** is only reused when its uri is visible to the app (it is one of the user's
  own nodes, or access was granted at the full level); a hidden one falls back to `freedom://localhost`.
- **Professional mode** in Magic V2Ray owns the hand-written config, so it refuses a session with overrides.
- **Android 11+ package visibility.** The manifest declares `<queries>` for Magic V2Ray; without it `startActivity` cannot see the app.

## Build

```sh
./build.sh --test    # host tests (node choice, overrides JSON): needs a JDK only
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
| `MagicV2Ray.java` | The whole client of the API: connect (`uri` optional, `overrides`) / disconnect, info, state, active node + node access request, state-event listener; documented for reuse |
| `NodePicker.java` | The node choice: the connected node, else `freedom://localhost`; the fallback without permission; no Android dependency |
| `DnsOverrides.java` | The `overrides` JSON: Hijack DNS on, Proxy DNS `https://9.9.9.9/dns-query`; no Android dependency |
| `MainActivity.java` | Shows Magic V2Ray's info, resolves the node (asking for permission once), calls Magic V2Ray with the overrides, logs state events |
| `tests/NodePickerTest.java` | Host-side tests of the node choice, run by `./build.sh --test` and by CI |
| `tests/DnsOverridesTest.java` | Host-side check of the overrides JSON (balanced, exact keys, nothing else) |

## License

Released into the public domain as an example: copy whatever you need.
