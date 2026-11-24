package com.example.magicv2raydeeplink;

/** Host-side check of NodePicker (plain JDK): connected node is reused, everything else is freedom://localhost. */
public class NodePickerTest {

    private static int failures;

    private static void eq(Object want, Object got, String what) {
        boolean ok = want == null ? got == null : want.equals(got);
        System.out.println((ok ? "ok    " : "FAIL  ") + what + (ok ? "" : "  (want " + want + ", got " + got + ")"));
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        String F = NodePicker.FREEDOM;
        eq("nodeid://abc_1", NodePicker.pick(true, "selected", "nodeid://abc_1"), "connected, user's node -> that node");
        eq("vless://u@h:443?x=1#n", NodePicker.pick(true, "selected", "vless://u@h:443?x=1#n"), "full level uri is passed through");
        eq("nodeid://abc_1", NodePicker.pick(true, "session", "nodeid://abc_1"), "another app's session on a user's node -> that node");
        eq("chain://localhost/?hopList=%5B%22nodeid%3A%2F%2Fa%22%5D",
                NodePicker.pick(true, "session", "chain://localhost/?hopList=%5B%22nodeid%3A%2F%2Fa%22%5D"), "chain is passed through");
        eq(F, NodePicker.pick(false, "selected", "nodeid://abc_1"), "not connected -> freedom");
        eq(F, NodePicker.pick(true, "direct", F), "connected on the no-proxy node -> freedom");
        eq(F, NodePicker.pick(true, "direct", "nodeid://abc_1"), "source direct always wins");
        eq(F, NodePicker.pick(true, "selected", ""), "empty uri -> freedom");
        eq(F, NodePicker.pick(true, "selected", null), "null uri -> freedom");
        eq(F, NodePicker.pick(true, "session", "vless://hidden"), "hidden session node -> freedom");
        eq(F, NodePicker.pick(true, "session", "chain://localhost/?hopList=%5B%22vless%3A%2F%2Fhidden%22%5D"), "chain with a hidden hop -> freedom");
        eq(F, NodePicker.pick(true, "selected", "FREEDOM://localhost"), "freedom in any case -> canonical freedom");

        eq(null, NodePicker.fallback(true, true, false), "no permission, connected on the user's node -> keep it (no uri)");
        eq(F, NodePicker.fallback(true, false, false), "no permission, not connected -> freedom");
        eq(F, NodePicker.fallback(true, true, true), "no permission, another app's session -> freedom");
        eq(F, NodePicker.fallback(false, true, false), "no permission, state unknown -> freedom");

        eq(Boolean.TRUE, NodePicker.usableInLink("freedom://localhost"), "link: freedom is fine");
        eq(Boolean.TRUE, NodePicker.usableInLink("vless://u@h:443#n"), "link: a real node link is fine");
        eq(Boolean.FALSE, NodePicker.usableInLink("nodeid://abc"), "link: nodeid is refused by Magic V2Ray");
        eq(Boolean.FALSE, NodePicker.usableInLink("chain://localhost/?hopList=%5B%22nodeid%3A%2F%2Fa%22%5D"), "link: chain with a nodeid hop");

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("all checks passed");
    }
}
