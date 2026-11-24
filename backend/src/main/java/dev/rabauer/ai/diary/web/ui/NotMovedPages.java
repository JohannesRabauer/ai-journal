package dev.rabauer.ai.diary.web.ui;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Renders the temporary "not moved yet" page for a final Qute route that is not built yet, so the navigation never
 * leads to a 404. Each phase of docs/MIGRATION-QUTE.md deletes the resource method of the route it implements; this
 * class goes with phase 4. The link goes to the previous (Vaadin) interface on the same host, port
 * {@code journal.web.legacy-port}.
 */
@ApplicationScoped
public class NotMovedPages {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance notMoved(Shell shell, Messages t, NotMoved page);
    }

    /** {@code legacyUrl} is null when the previous interface's port is not configured. */
    public record NotMoved(String titleKey, String legacyUrl) {
    }

    @Inject
    ShellFactory shells;
    @ConfigProperty(name = "journal.web.legacy-port")
    Optional<Integer> legacyPort;

    /**
     * @param activeHref  nav item to mark current
     * @param legacyPath  the same page in the previous interface
     */
    public TemplateInstance render(List<Locale> acceptable, URI requestUri, String activeHref, String titleKey,
                                   String legacyPath) {
        Messages t = Messages.forAcceptable(acceptable);
        String url = legacyPort.map(p -> requestUri.getScheme() + "://" + requestUri.getHost() + ":" + p + legacyPath)
                .orElse(null);
        return Templates.notMoved(shells.create(t, activeHref), t, new NotMoved(titleKey, url));
    }
}
