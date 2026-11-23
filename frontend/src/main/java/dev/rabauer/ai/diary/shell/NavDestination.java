package dev.rabauer.ai.diary.shell;

import com.vaadin.flow.component.icon.VaadinIcon;

import java.util.List;

/** Primary navigation entries (SPEC section 12). {@code route} is relative, "" is the home route. */
public record NavDestination(String route, String labelKey, VaadinIcon icon) {

    public static final List<NavDestination> ALL = List.of(
            new NavDestination("", "nav.today", VaadinIcon.EDIT),
            new NavDestination("entries", "nav.journal", VaadinIcon.BOOK),
            new NavDestination("memory", "nav.memory", VaadinIcon.LIGHTBULB),
            new NavDestination("gym", "nav.gym", VaadinIcon.COMPILE),
            new NavDestination("weekly", "nav.weekly", VaadinIcon.CALENDAR),
            new NavDestination("settings", "nav.settings", VaadinIcon.COG));

    /** The home route matches only itself; others match their path and sub-paths (entries/5/coach -> Journal). */
    public boolean isActive(String currentPath) {
        String path = currentPath == null ? "" : currentPath.replaceAll("^/+|/+$", "");
        if (route.isEmpty()) {
            return path.isEmpty();
        }
        return path.equals(route) || path.startsWith(route + "/");
    }
}
