package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.system.SystemInfoService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/** Builds the shell model; the cloud decision comes from {@link SystemInfoService}, shared with the JSON API. */
@ApplicationScoped
public class ShellFactory {

    private record Dest(String href, String labelKey, String icon, boolean secondary) {
    }

    /** Final route scheme of the Qute UI (docs/MIGRATION-QUTE.md): entry detail is /journal/{id}, coach /journal/{id}/reflect. */
    private static final List<Dest> DESTINATIONS = List.of(
            new Dest("/", "nav.today", "pen", false),
            new Dest("/journal", "nav.journal", "book", false),
            new Dest("/memory", "nav.memory", "bulb", false),
            new Dest("/gym", "nav.gym", "brain", false),
            new Dest("/weekly", "nav.weekly", "calendar", false),
            new Dest("/settings", "nav.settings", "cog", true));

    @Inject
    SystemInfoService system;

    /** @param activeHref the path of the current page; its nav item (or the item it is nested under) is marked current */
    public Shell create(Messages messages, String activeHref) {
        boolean cloud = system.cloud();
        List<Shell.NavItem> nav = DESTINATIONS.stream()
                .map(d -> new Shell.NavItem(d.href(), d.labelKey(), d.icon(), isActive(d.href(), activeHref), d.secondary()))
                .toList();
        Shell.NavItem settings = nav.stream().filter(Shell.NavItem::secondary).findFirst().orElseThrow();
        return new Shell(messages.lang(), cloud, cloud ? "cloud" : "local", cloud ? "cloud" : "lock",
                cloud ? "status.cloud" : "status.local", nav, settings);
    }

    static boolean isActive(String navHref, String activeHref) {
        return activeHref.equals(navHref) || (!"/".equals(navHref) && activeHref.startsWith(navHref + "/"));
    }
}
