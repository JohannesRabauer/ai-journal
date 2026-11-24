package dev.rabauer.ai.diary.security;

import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;

import java.util.Arrays;
import java.util.Optional;

/**
 * Form-token plumbing around {@link Csrf}:
 * <ul>
 *   <li>issues the {@code diary-csrf} session cookie on the first HTML page a browser loads (decided before the
 *       page renders, so the token in the page matches the cookie);</li>
 *   <li>for a write accepted by {@link RequestGuardFilter} only because it is a form post, insists that the matched
 *       route declares a {@code @FormParam("_csrf")} parameter, so a route cannot silently skip the token.</li>
 * </ul>
 */
public class CsrfCookieFilter {

    @Inject
    CsrfTokens tokens;

    @ServerRequestFilter
    public void issue(ContainerRequestContext ctx, RoutingContext rc) {
        if (!"GET".equals(ctx.getMethod())) {
            return;
        }
        Cookie cookie = rc.request().getCookie(CsrfTokens.COOKIE);
        if (cookie == null || !tokens.validSession(cookie.getValue())) {
            rc.put(Csrf.ISSUED, tokens.newSession());
        }
    }

    @ServerResponseFilter
    public void setCookie(RoutingContext rc, ContainerResponseContext response) {
        String session = rc.get(Csrf.ISSUED);
        MediaType type = response.getMediaType();
        if (session != null && type != null && MediaType.TEXT_HTML_TYPE.isCompatible(type)) {
            response.getHeaders().add("Set-Cookie", tokens.setCookie(session, rc.request().isSSL()));
        }
    }

    @ServerRequestFilter
    public Optional<Response> routeMustDeclareToken(ContainerRequestContext ctx, ResourceInfo info) {
        if (ctx.getProperty(RequestGuardFilter.FORM_TOKEN_PENDING) == null) {
            return Optional.empty();
        }
        boolean declared = info.getResourceMethod() != null && Arrays.stream(info.getResourceMethod().getParameters()).anyMatch(p -> {
            jakarta.ws.rs.FormParam f = p.getAnnotation(jakarta.ws.rs.FormParam.class);
            return f != null && CsrfTokens.FIELD.equals(f.value());
        });
        return declared ? Optional.empty() : Optional.of(RequestGuardFilter.csrfRequired());
    }
}
