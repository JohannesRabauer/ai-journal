package dev.rabauer.ai.diary.web.ui;

import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;

/**
 * Temporary pages for the top-level routes that are not built yet (see {@link NotMovedPages}). The entry routes
 * {@code /journal/{id}} and {@code /journal/{id}/reflect} live in {@link JournalResource}, because one URL prefix can
 * only belong to one resource class.
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
public class NotMovedResource {

    @Inject
    NotMovedPages pages;

    @GET
    public TemplateInstance today(@Context HttpHeaders h, @Context UriInfo u) {
        return page(h, u, "/", "nav.today", "/");
    }

    private TemplateInstance page(HttpHeaders h, UriInfo u, String active, String titleKey, String legacyPath) {
        return pages.render(h.getAcceptableLanguages(), u.getRequestUri(), active, titleKey, legacyPath);
    }
}
