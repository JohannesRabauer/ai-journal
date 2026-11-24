package dev.rabauer.ai.diary.security;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.ForbiddenException;

/**
 * Per-request access to the form token. Templates emit the hidden field with {@code {#csrf /}} (which uses
 * {@code {inject:csrf.token}}); a form route declares {@code @FormParam("_csrf") String csrf} and calls
 * {@link #require(String)} before it changes anything.
 */
@Named("csrf")
@ApplicationScoped
public class Csrf {

    /** Routing-context attribute holding a session value issued during this very request. */
    static final String ISSUED = "diary.csrf.session";

    @Inject
    CsrfTokens tokens;
    @Inject
    CurrentVertxRequest current;

    /** Token for the hidden field; empty when no request is active. */
    public String token() {
        String session = session();
        return session == null ? "" : tokens.tokenFor(session);
    }

    public String field() {
        return CsrfTokens.FIELD;
    }

    /** Throws 403 unless the submitted token matches the session cookie. */
    public void require(String submitted) {
        if (!tokens.verify(session(), submitted)) {
            throw new ForbiddenException("Invalid or missing form token. Reload the page and try again.");
        }
    }

    private String session() {
        RoutingContext rc = current.getCurrent();
        if (rc == null) {
            return null;
        }
        Cookie cookie = rc.request().getCookie(CsrfTokens.COOKIE);
        if (cookie != null && tokens.validSession(cookie.getValue())) {
            return cookie.getValue();
        }
        return rc.get(ISSUED);
    }
}
