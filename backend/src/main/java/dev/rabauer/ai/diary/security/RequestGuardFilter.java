package dev.rabauer.ai.diary.security;

import dev.rabauer.ai.diary.api.common.ErrorDto;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Protects the unauthenticated local API against browsers of other websites (CSRF) and DNS rebinding.
 * <ul>
 *   <li>Host must be a loopback name, the compose service name or a configured extra host (421 otherwise).</li>
 *   <li>{@code Sec-Fetch-Site: cross-site} and any {@code Origin} outside the allowlist are rejected (403).</li>
 *   <li>Every method except GET/HEAD/OPTIONS needs {@code X-AI-Diary: 1}. A custom header forces a CORS preflight,
 *       which fails because CORS is disabled, so "simple" cross-site requests (forms, no-cors fetch, sendBeacon) cannot write.</li>
 * </ul>
 */
public class RequestGuardFilter {

    public static final String HEADER = "X-AI-Diary";
    private static final Logger LOG = Logger.getLogger(RequestGuardFilter.class);
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final Set<String> hosts;
    private final Set<String> origins;

    public RequestGuardFilter(
            @ConfigProperty(name = "journal.http.allowed-hosts") Optional<List<String>> extraHosts,
            @ConfigProperty(name = "journal.http.allowed-origins") Optional<List<String>> allowedOrigins) {
        this.hosts = HostAllowlist.hosts(extraHosts.orElse(List.of()), "backend");
        this.origins = allowedOrigins.orElse(List.of()).stream()
                .map(o -> o.trim().toLowerCase(Locale.ROOT)).filter(o -> !o.isEmpty()).collect(Collectors.toSet());
    }

    /**
     * HTTP/1.1 sends a Host header. HTTP/2 (the Java client of the frontend upgrades to h2c) sends the authority
     * as a pseudo header and no Host header, so fall back to the host of the request URI, which is that authority.
     */
    static String effectiveHost(String hostHeader, java.net.URI requestUri) {
        if (hostHeader != null) {
            return hostHeader;
        }
        return requestUri == null ? null : requestUri.getAuthority();
    }

    @ServerRequestFilter(preMatching = true)
    public Optional<Response> guard(ContainerRequestContext ctx) {
        String hostHeader = effectiveHost(ctx.getHeaderString("Host"), ctx.getUriInfo().getRequestUri());
        if (!HostAllowlist.allowed(hostHeader, hosts)) {
            String h = String.valueOf(hostHeader);
            LOG.warnf("Request rejected: unexpected Host header '%s'", h.length() > 80 ? h.substring(0, 80) : h);
            return Optional.of(Response.status(421).build());
        }
        if ("cross-site".equalsIgnoreCase(ctx.getHeaderString("Sec-Fetch-Site"))) {
            return Optional.of(forbidden("Cross-site requests are not allowed."));
        }
        String origin = ctx.getHeaderString("Origin");
        if (origin != null && !origins.contains(origin.trim().toLowerCase(Locale.ROOT))) {
            return Optional.of(forbidden("Requests from other origins are not allowed."));
        }
        if (!SAFE_METHODS.contains(ctx.getMethod().toUpperCase(Locale.ROOT)) && !"1".equals(ctx.getHeaderString(HEADER))) {
            LOG.warnf("Write request rejected: header %s missing", HEADER);
            return Optional.of(Response.status(403).type(MediaType.APPLICATION_JSON)
                    .entity(new ErrorDto("CSRF_HEADER_REQUIRED", "This request needs the " + HEADER + ": 1 header.")).build());
        }
        return Optional.empty();
    }

    private static Response forbidden(String message) {
        return Response.status(403).type(MediaType.APPLICATION_JSON).entity(new ErrorDto("ORIGIN_NOT_ALLOWED", message)).build();
    }
}

