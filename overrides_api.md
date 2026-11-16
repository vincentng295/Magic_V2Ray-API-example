# Magic V2Ray – Control API: `overrides`

Lets another app (or a web page) start a **temporary session**: connect to a node and/or change settings
for the duration of that session only. The user's saved nodes, settings, exclude list and hosts are never
modified. Source of truth: `ExternalSession.java` (`sanitizeOverrides`) and `external.js`.

## 1. Ways to send a request

| Channel | Format |
|---|---|
| Deep link | `magicv2ray://connect?uri=<node uri>&name=<name>&exclude=<pkg,pkg>&overrides=<base64url JSON>` |
| Intent (activity) | action `com.huskydg.magicv2ray.action.CONNECT`, extras below |
| Broadcast | same action, handled by `ExternalControlReceiver` |
| Disconnect | `magicv2ray://disconnect` or action `com.huskydg.magicv2ray.action.DISCONNECT` |

| Param / extra | Type | Notes |
|---|---|---|
| `uri` | string | Node URI (`vless://`, `trojan://`, `ss://`, ...). Max 8192 chars. **Optional when `overrides` is given**: the node the user selected keeps being used. |
| `name` | string | Display name (status bar, notification, tile). Max 60 chars, control chars stripped. Display only, never trusted. |
| `exclude` | string (`a,b;c`) or `String[]` | Packages to keep out of the VPN **for this session** (e.g. the caller itself). Max 8, must look like a package name. Not the same as `overrides.exclude` (see 3.3). |
| `overrides` | JSON string (intent) / base64url JSON (deep link) | Setting overrides, described below. Max 256 KB decoded. |

A request with neither a valid `uri` nor valid `overrides` is rejected.

Deep link encoding: UTF-8 JSON → base64url, **no padding** (`URL_SAFE | NO_WRAP | NO_PADDING`).
In an intent extra you may pass the raw JSON (string starting with `{`) instead.

## 2. Full example

```json
{
  "traffic": { "mux": true, "mtu": 1400, "directDns": "quic+local://223.5.5.5" },
  "network": { "networkMode": 1, "allowTether": false, "bypassIfaces": ["tailscale0"], "bypassVpnService": true },
  "exclude": { "apps": ["com.example.app"], "mode": "add", "enabled": true },
  "routing": { "domainStrategy": "IPIfNonMatch", "mode": "prepend",
               "rules": [ { "domain": "geosite:google", "outboundTag": "proxy", "remarks": "temp" } ] },
  "hosts":   { "mode": "merge", "entries": { "example.com": "1.2.3.4", "b.com": ["::1", "10.0.0.1"] } }
}
```

Meaning: enable mux and set MTU 1400; direct DNS over `quic+local://223.5.5.5`; network mode 1 with tethering
off; interface `tailscale0` bypasses Xray; VPN-service bypass on; add `com.example.app` to the user's exclude
list; put a temporary Google→proxy rule in front of the user's rules; add two host entries on top of the user's hosts.

Every section is optional. Unknown sections and keys are ignored.

### Sending it

```java
Intent i = new Intent("com.huskydg.magicv2ray.action.CONNECT")
        .setPackage("com.huskydg.magicv2ray")
        .putExtra("uri", "vless://...")
        .putExtra("name", "My VPN")
        .putExtra("exclude", new String[]{ getPackageName() })
        .putExtra("overrides", overridesJson);
startActivity(i);   // or sendBroadcast(i)
```

```js
const b64 = btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(ov))))
  .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
location.href = 'magicv2ray://connect?uri=' + encodeURIComponent(uri) + '&overrides=' + b64;
```

## 3. Sections

**Validation rule for all sections:** only whitelisted keys of the exact type and range are applied.
A wrong type or out-of-range value is **dropped silently, never coerced** (e.g. `"mtu": "1400"` is ignored).
If the whole JSON is invalid / not decodable, all overrides are discarded.

### 3.1 `traffic` (same keys as the Traffic settings tab)

| Key | Type / allowed values |
|---|---|
| `loglevel` | `debug` \| `info` \| `warning` \| `error` \| `none` |
| `sniffing`, `routeOnly`, `dnsViaProxy`, `hijackDns`, `mux`, `fragment`, `localDns`, `fakeDnsLocal` | boolean |
| `queryStrategy` | `UseIP` \| `UseIPv4` \| `UseIPv6` \| `UseSystem` |
| `mux_connections` | integer 1–1024 |
| `mtu` | integer 576–9000 |
| `fragment_packets`, `fragment_length`, `fragment_interval` | string, max 32 |
| `pinnedPeerCertSha256` | string, max 512 |
| `foreignDns` | string, max 4000 (comma separated servers) |
| `directDns` | string, max 500 |
| `directDnsDomains` | string, max 8000 |
| `dnsDisableCache`, `dnsServeStale`, `dnsDisableFallback`, `dnsDisableFallbackIfMatch`, `dnsParallelQuery`, `dnsUseSystemHosts` | boolean |
| `dnsServeExpiredTTL` | integer 0–86400 |

### 3.2 `network` (same keys as the Network settings tab)

| Key | Type / allowed values |
|---|---|
| `networkMode` | integer 0–2 (same values as the Network mode setting) |
| `allowTether`, `enableIPv6`, `enableIPv6ULA`, `bypassVpnService` | boolean |
| `upstreamIface` | `auto` or an interface name (`[A-Za-z0-9_.:+-]`, 1–15 chars) |
| `lanProxyCidrs` | string, max 2000, **space-separated** (no commas). Each entry must be **exactly** one of the built-in ranges listed below; the list means "send these ranges through Xray instead of bypassing them". Default `""` = every range bypasses Xray. Anything else is ignored silently (the Java side only checks the characters, the native side matches whole strings). Loopback can never be listed. |
| `bypassIfaces` | array of interface names, **max 8**. An empty array means "no bypass interface". Invalid names are skipped. |

Accepted `lanProxyCidrs` values (exact strings):

- IPv4: `10.0.0.0/8` `100.64.0.0/10` `169.254.0.0/16` `172.16.0.0/12` `192.0.0.0/24` `192.0.2.0/24` `192.88.99.0/24` `192.168.0.0/16` `198.51.100.0/24` `203.0.113.0/24` `224.0.0.0/4` `240.0.0.0/4` `255.255.255.255/32`
- IPv6: `::ffff:0:0/96` `64:ff9b::/96` `100::/64` `2001::/32` `2001:10::/28` `2001:20::/28` `2001:db8::/32` `2002::/16` `fc00::/7` `fe80::/10` `ff00::/8`

Example: `"lanProxyCidrs": "192.168.0.0/16 10.0.0.0/8"`. Arbitrary CIDRs such as `10.1.0.0/16` are not supported.

### 3.3 `exclude` (Exclude apps list)

| Key | Notes |
|---|---|
| `apps` | Array of package names, max 500, invalid names skipped. |
| `mode` | `"add"` = user's list + `apps`; anything else = `"replace"` (**default**). |
| `enabled` | boolean, default `true`. Turns the Exclude-apps master switch on/off for the session. |

The top-level `exclude` request parameter (section 1) is a separate, smaller list (max 8) that is always added for the session.

### 3.4 `routing`

| Key | Notes |
|---|---|
| `domainStrategy` | `AsIs` \| `IPIfNonMatch` \| `IPOnDemand` |
| `rules` | Array, max 500 rules. |
| `mode` | `"prepend"` = rules go before the user's rules; anything else = `"replace"` (**default**). Only used when `rules` is present. |

Rule fields (all strings unless noted; control characters stripped):

| Field | Max length | Notes |
|---|---|---|
| `outboundTag` | 16 | **Only `proxy`, `direct`, `block`.** A rule with any other tag (saved nodes, policy groups) is dropped: those belong to the user. |
| `domain` | 4000 | Xray matcher syntax, e.g. `geosite:google`, `domain:example.com` |
| `ip` | 4000 | e.g. `geoip:private`, CIDR |
| `port` | 200 | |
| `protocol` | 100 | |
| `network` | 20 | |
| `remarks` | 80 | Label shown in the Routing tab |
| `enabled` | boolean | default `true` |

### 3.5 `hosts` (Custom hosts)

| Key | Notes |
|---|---|
| `entries` | Object `domain → IP` or `domain → [IP, ...]`. Max 2000 domains, max 8 IPs per domain. IPv4 and IPv6 allowed. Invalid domains / IPs are skipped. |
| `mode` | `"merge"` = added on top of the user's hosts; anything else = `"replace"` (**default**: the user's hosts are not used during the session). |

Domain pattern: `[A-Za-z0-9*_.-]{1,253}`.

## 4. NOT overridable (dropped even if sent)

`exposeSocks*` (would open a LAN proxy), `proMode`, `shizukuShare*`, language / theme, and every key not listed above.
Routing rules pointing at saved nodes or policy groups are also refused.

## 5. Behaviour and security

- **A request is only a request.** It is parked and handed to the WebUI, which shows a confirmation (or a
  notification "tap to review" when the app is in the background). Nothing is applied before the user agrees.
- **Trust.** The user can choose "Always allow" for an app. Trust is bound to the app's signing certificate and to a
  hash of the overrides it was allowed with: the same overrides pass silently, **any new or changed overrides ask again**.
  Deep links and browsers are never trustable (Android cannot say which page opened the link).
- **Refused when** Professional mode (hand-written `config.json`) is on, or the generated config is invalid.
- **Temporary.** Merged settings, the config, exclude list and bypass-interface file are written to a RAM/cache
  override dir only (root: tmpfs under `/dev`; non-root: app cache). The user's files are never touched. Ending the
  session (`disconnect`, notification button, tile, in-app button) deletes that dir and restarts the engine on the user's own
  setup, or stops it if it was not running before. A session never survives a reboot.
- **One session at a time.** A new connect while a session is active replaces it and keeps the original "was running" state.
- **Export app logs** during a session reports the session's node, settings, caller and `config.json`
  (see `session.txt`), with the user's own values shown for comparison.

## 6. Tips for integrators

- Send the smallest set of overrides you need; large or changing overrides mean more confirmation prompts.
- Pass your own package in `exclude` when your app talks to a server on the same device, to avoid a traffic loop.
- Check the result through the status banner / notification; the request itself returns nothing.