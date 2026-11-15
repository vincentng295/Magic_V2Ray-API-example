package com.example.magicv2raydeeplink;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host-side check of DnsOverrides (plain JDK, no JSON library): the text must be well-formed JSON and carry exactly
 * what Magic V2Ray's whitelist accepts: traffic.hijackDns = false and routing in mode replace with
 * "port 53 -> proxy", then "else direct" for IPs and for domains, in that order.
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
        check(j.contains("\"traffic\":{\"hijackDns\":false}"), "Hijack DNS is turned off");
        check(j.contains("\"mode\":\"replace\""), "routing mode is replace");

        // The rules, in order: the first match wins in Xray.
        Matcher m = Pattern.compile("\\{\"remarks\":\"[^\"]*\"[^{}]*\\}").matcher(j.substring(j.indexOf("\"rules\"")));
        String[] rules = new String[3];
        int n = 0;
        while (m.find() && n < 4) {
            if (n < 3) rules[n] = m.group();
            n++;
        }
        check(n == 3, "exactly three routing rules");
        if (n == 3) {
            check(rules[0].contains("\"port\":\"53\"") && rules[0].contains("\"outboundTag\":\"proxy\""),
                    "rule 1: port 53 -> proxy");
            check(rules[1].contains("\"ip\":\"0.0.0.0/0,::/0\"") && rules[1].contains("\"outboundTag\":\"direct\""),
                    "rule 2: any ip -> direct");
            check(rules[2].contains("\"domain\":\"regexp:.+\"") && rules[2].contains("\"outboundTag\":\"direct\""),
                    "rule 3: any domain -> direct");
            check(!rules[1].contains("\"port\"") && !rules[2].contains("\"port\""), "the catch-alls have no port condition");
        }
        // Magic V2Ray drops any outboundTag that is not one of these: a node or group name would silently vanish.
        check(!Pattern.compile("\"outboundTag\":\"(?!proxy\"|direct\")").matcher(j).find(), "only proxy / direct tags");
        check(j.length() < 256 * 1024, "far below Magic V2Ray's 256 KB limit");

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("all checks passed");
    }
}
