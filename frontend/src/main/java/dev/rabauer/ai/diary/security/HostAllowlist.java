package dev.rabauer.ai.diary.security;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Host header allowlist (DNS rebinding protection). Loopback names are always allowed, any port. */
public final class HostAllowlist {

    private HostAllowlist() {
    }

    public static Set<String> hosts(List<String> extra) {
        Set<String> set = new HashSet<>(Set.of("localhost", "127.0.0.1", "[::1]"));
        for (String e : extra) {
            String h = hostOf(e);
            if (h != null && !h.isEmpty()) {
                set.add(h);
            }
        }
        return set;
    }

    public static boolean allowed(String hostHeader, Set<String> hosts) {
        String h = hostOf(hostHeader);
        return h != null && hosts.contains(h);
    }

    /** Host without port, lower case; IPv6 literals keep their brackets. Null when absent or malformed. */
    public static String hostOf(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            return end < 0 ? null : v.substring(0, end + 1);
        }
        int colon = v.indexOf(':');
        return colon < 0 ? v : v.substring(0, colon);
    }
}
