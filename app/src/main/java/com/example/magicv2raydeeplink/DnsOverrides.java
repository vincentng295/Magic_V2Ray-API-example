package com.example.magicv2raydeeplink;

/**
 * The temporary setting overrides (the {@code overrides} parameter of the Magic V2Ray connect API) this app asks for.
 * They apply only while the session is connected and are never saved by Magic V2Ray.
 *
 * <pre>
 * traffic: hijackDns = false          the device's DNS queries are NOT answered by Xray's own DNS module, so
 *                                     every one of them travels as plain port-53 traffic and reaches this proxy
 * routing (mode replace):
 *   1. port 53            -> proxy    (socks://127.0.0.1:5353: this app rewrites it to 9.9.9.9)
 *   2. any IP             -> direct   (0.0.0.0/0, ::/0)
 *   3. any domain         -> direct   (destinations that still carry a name, e.g. sniffed ones)
 * </pre>
 *
 * "All 53 -> proxy, else direct" needs rules 2 and 3 because Magic V2Ray drops a rule that has no match condition
 * (a bare catch-all) and because an {@code ip} rule never matches a destination that is still a domain
 * ({@code domainStrategy} AsIs). Without them, everything not matched would fall to Magic V2Ray's default: proxy.
 *
 * Plain JSON text on purpose: no Android dependency, so the host tests can check it.
 * Magic V2Ray accepts only whitelisted keys and {@code outboundTag} of proxy / direct / block; nothing else is needed.
 */
final class DnsOverrides {

    static final String JSON = "{"
            + "\"traffic\":{\"hijackDns\":false},"
            + "\"routing\":{\"mode\":\"replace\",\"rules\":["
            + "{\"remarks\":\"DNS (port 53) -> proxy\",\"port\":\"53\",\"outboundTag\":\"proxy\"},"
            + "{\"remarks\":\"else direct (ip)\",\"ip\":\"0.0.0.0/0,::/0\",\"outboundTag\":\"direct\"},"
            + "{\"remarks\":\"else direct (domain)\",\"domain\":\"regexp:.+\",\"outboundTag\":\"direct\"}"
            + "]}"
            + "}";

    private DnsOverrides() {
    }
}
