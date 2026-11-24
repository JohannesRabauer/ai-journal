package dev.rabauer.ai.diary.shell;

import java.util.List;

/**
 * Primary navigation entries (SPEC section 12). {@code route} is relative, "" is the home route; {@code icon} is a
 * symbol id in the {@link DiaryIcons} sprite.
 */
public record NavDestination(String route, String labelKey, String icon) {

    public static final List<NavDestination> ALL = List.of(
            new NavDestination("", "nav.today", "pen"),
            new NavDestination("entries", "nav.journal", "book"),
            new NavDestination("memory", "nav.memory", "bulb"),
            new NavDestination("gym", "nav.gym", "brain"),
            new NavDestination("weekly", "nav.weekly", "calendar"),
            new NavDestination("settings", "nav.settings", "cog"));

    /** The home route matches only itself; others match their path and sub-paths (entries/5/coach -> Journal). */
    public boolean isActive(String currentPath) {
        String path = currentPath == null ? "" : currentPath.replaceAll("^/+|/+$", "");
        if (route.isEmpty()) {
            return path.isEmpty();
        }
        return path.equals(route) || path.startsWith(route + "/");
    }

    /** Settings leaves the mobile bottom bar (at most five items there) and is reached from the mobile header. */
    public boolean secondary() {
        return "settings".equals(route);
    }
}
