package com.example.magicv2raydeeplink;

/**
 * Which node should the session use? Pure string logic (no Android, no JSON) so the host tests can check it.
 *
 * <ul>
 *   <li>Magic V2Ray is connected to a node (the user's own pick, or a node set by another app that we may use):
 *       use that node, as the {@code uri} the API handed us ({@code nodeid://<id>} for the anonymous level);</li>
 *   <li>otherwise (not connected, or the built-in no-proxy node): {@link #FREEDOM}.</li>
 * </ul>
 */
final class NodePicker {

    /** The built-in "no proxy" node: Xray as a pure router, every routing rule still applies. */
    static final String FREEDOM = "freedom://localhost";

    private NodePicker() {
    }

    /**
     * @param connected from {@code getActiveNode.connected}
     * @param source    from {@code getActiveNode.source}: "session", "selected" or "direct"
     * @param uri       from {@code getActiveNode.node.uri}
     * @return the {@code uri} for the connect request, never null
     */
    static String pick(boolean connected, String source, String uri) {
        if (!connected) return FREEDOM;
        if ("direct".equals(source)) return FREEDOM;
        if (uri == null || uri.isEmpty()) return FREEDOM;
        if (isFreedom(uri)) return FREEDOM;
        if (isHidden(uri)) return FREEDOM;      // a node an app set that an anonymous reader may not see
        return uri;
    }

    /**
     * No permission to read nodes (the user said no, or Magic V2Ray is older than API level 2), but the state is
     * public: if it is connected through the user's own node, leave {@code uri} out and Magic V2Ray keeps using that
     * node; with another app's session running we cannot tell which node it is, so use {@link #FREEDOM}.
     *
     * @return {@code null} = send no uri (keep the selected node), otherwise the uri to send
     */
    static String fallback(boolean stateKnown, boolean connected, boolean sessionActive) {
        if (stateKnown && connected && !sessionActive) return null;
        return FREEDOM;
    }

    static boolean isFreedom(String uri) {
        return uri != null && uri.regionMatches(true, 0, "freedom://", 0, 10);
    }

    /** A deep link can never carry {@code nodeid://} (alone or as a hop): Magic V2Ray refuses it, the caller is unknown. */
    static boolean usableInLink(String uri) {
        if (uri == null) return false;
        String u = uri.toLowerCase(java.util.Locale.ROOT);
        return !u.contains("nodeid://") && !u.contains("nodeid%3a%2f%2f");
    }

    /** {@code <scheme>://hidden} (or a chain with such a hop): the node exists but this app may not see it. */
    static boolean isHidden(String uri) {
        if (uri == null) return false;
        String u = uri.toLowerCase(java.util.Locale.ROOT);
        return u.contains("://hidden") || u.contains("%3a%2f%2fhidden");
    }
}
