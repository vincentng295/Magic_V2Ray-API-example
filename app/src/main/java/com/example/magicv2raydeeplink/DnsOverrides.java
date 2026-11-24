package com.example.magicv2raydeeplink;

/**
 * The temporary setting overrides (the {@code overrides} parameter of the Magic V2Ray connect API) this app asks for.
 * They apply only while the session is connected and are never saved by Magic V2Ray.
 *
 * <pre>
 * traffic.hijackDns  = true                            Xray answers every DNS query of the device itself
 * traffic.foreignDns = "https://9.9.9.9/dns-query"     the "Proxy DNS" upstream: DNS over HTTPS to Quad9
 * </pre>
 *
 * Nothing else is overridden: the user's routing rules, hosts, exclude list and the other settings stay as they are,
 * and no local proxy is needed any more (the old version rewrote port 53 through a SOCKS5 server).
 *
 * Plain JSON text on purpose: no Android dependency, so the host tests can check it.
 * Magic V2Ray accepts only whitelisted keys of the right type ({@code foreignDns}: string, max 4000 chars,
 * comma separated servers).
 */
final class DnsOverrides {

    /** The DNS server used as "Proxy DNS" for the session. */
    static final String PROXY_DNS = "https://9.9.9.9/dns-query";

    static final String JSON = "{"
            + "\"traffic\":{\"hijackDns\":true,\"foreignDns\":\"" + PROXY_DNS + "\"}"
            + "}";

    private DnsOverrides() {
    }
}
