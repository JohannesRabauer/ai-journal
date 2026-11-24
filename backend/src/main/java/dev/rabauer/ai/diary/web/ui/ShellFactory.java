package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.config.JournalConfig;
import dev.rabauer.ai.diary.config.ProviderProducers;
import dev.rabauer.ai.diary.config.ProviderSelection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/** Builds the shell model. Mirrors SystemResource.info(): cloud when a provider is external or profile lite-cloud. */
@ApplicationScoped
public class ShellFactory {

    private record Dest(String href, String labelKey, String icon, boolean secondary) {
    }

    // Same six destinations as the Vaadin NavDestination; only /journal exists in the spike.
    private static final List<Dest> DESTINATIONS = List.of(
            new Dest("/today", "nav.today", "pen", false),
            new Dest("/journal", "nav.journal", "book", false),
            new Dest("/memory", "nav.memory", "bulb", false),
            new Dest("/gym", "nav.gym", "brain", false),
            new Dest("/weekly", "nav.weekly", "calendar", false),
            new Dest("/settings", "nav.settings", "cog", true));

    @Inject
    JournalConfig config;
    @Inject
    ProviderProducers providers;

    public Shell create(Messages messages, String activeHref) {
        boolean cloud = JournalConfig.PROFILE_LITE_CLOUD.equals(config.profile());
        for (ProviderSelection s : providers.selections().values()) {
            cloud |= s.external();
        }
        List<Shell.NavItem> nav = DESTINATIONS.stream()
                .map(d -> new Shell.NavItem(d.href(), d.labelKey(), d.icon(),
                        activeHref.equals(d.href()) || activeHref.startsWith(d.href() + "/"), d.secondary()))
                .toList();
        Shell.NavItem settings = nav.stream().filter(Shell.NavItem::secondary).findFirst().orElseThrow();
        // Offline never applies here: the page is rendered by the backend itself.
        return new Shell(messages.lang(), cloud, cloud ? "cloud" : "local", cloud ? "cloud" : "lock",
                cloud ? "status.cloud" : "status.local", nav, settings);
    }
}
