package dev.rabauer.ai.diary.web.ui;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

/** Hardening headers for server-rendered HTML. No inline script or style is allowed; everything is same-origin. */
public class HtmlSecurityHeaders {

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    @ServerResponseFilter
    public void add(ContainerRequestContext request, ContainerResponseContext response) {
        MediaType type = response.getMediaType();
        if (type == null || !MediaType.TEXT_HTML_TYPE.isCompatible(type)) {
            return;
        }
        var h = response.getHeaders();
        h.putSingle("Content-Security-Policy", CSP);
        h.putSingle("X-Content-Type-Options", "nosniff");
        h.putSingle("Referrer-Policy", "no-referrer");
        h.putSingle("X-Frame-Options", "DENY");
        // Journal text is personal: never keep it in shared or disk caches.
        h.putSingle("Cache-Control", "no-store");
    }
}
