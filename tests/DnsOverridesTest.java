package com.example.magicv2raydeeplink;

/**
 * Host-side check of DnsOverrides (plain JDK, no JSON library): well-formed JSON carrying exactly what Magic V2Ray's
 * whitelist accepts: traffic.hijackDns = true and traffic.foreignDns = the Quad9 DoH server, and nothing else
 * (no routing, no hosts: the user's own rules stay in force).
 */
public class DnsOverridesTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    /** Brackets and braces balance, outside strings. */
    private static boolean balanced(String s) {
        StringBuilder stack = new StringBuilder();
        boolean str = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (str) {
                if (c == '\\') i++;
                else if (c == '"') str = false;
            } else if (c == '"') str = true;
            else if (c == '{' || c == '[') stack.append(c);
            else if (c == '}' || c == ']') {
                if (stack.length() == 0) return false;
                char o = stack.charAt(stack.length() - 1);
                if ((c == '}') != (o == '{')) return false;
                stack.setLength(stack.length() - 1);
            }
        }
        return !str && stack.length() == 0;
    }

    public static void main(String[] args) {
        String j = DnsOverrides.JSON;
        check(balanced(j), "JSON is balanced");
        check(j.contains("\"hijackDns\":true"), "Hijack DNS is turned on");
        check(j.contains("\"foreignDns\":\"https://9.9.9.9/dns-query\""), "Proxy DNS is https://9.9.9.9/dns-query");
        check(DnsOverrides.PROXY_DNS.length() <= 4000, "foreignDns is within Magic V2Ray's 4000 chars");
        check(!j.contains("\"routing\""), "no routing override: the user's rules stay");
        check(!j.contains("\"hosts\"") && !j.contains("\"network\"") && !j.contains("\"exclude\""), "no other section");
        check(j.length() < 256 * 1024, "far below Magic V2Ray's 256 KB limit");

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("all checks passed");
    }
}
