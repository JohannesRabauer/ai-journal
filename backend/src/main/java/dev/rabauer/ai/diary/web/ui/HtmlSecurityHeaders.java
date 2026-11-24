package dev.rabauer.ai.diary.web.ui;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

/**
 * Hardening headers. Server-rendered HTML gets the full set (no inline script or style, everything same-origin).
 * Every {@code /api} response (JSON, audio, exports: all of it is journal content) gets {@code no-store} and
 * {@code nosniff}. Static assets under /css, /js and /icons are not touched and may be cached.
 */
public class HtmlSecurityHeaders {

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    @ServerResponseFilter
    public void add(ContainerRequestContext request, ContainerResponseContext response) {
        String path = request.getUriInfo().getPath();
        if (path.equals("/api") || path.startsWith("/api/")) {
            response.getHeaders().putSingle("X-Content-Type-Options", "nosniff");
            response.getHeaders().putSingle("Cache-Control", "no-store");
            return;
        }
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
