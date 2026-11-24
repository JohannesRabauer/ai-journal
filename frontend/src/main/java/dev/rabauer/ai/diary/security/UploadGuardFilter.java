package dev.rabauer.ai.diary.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * CSRF guard for the upload proxies ({@code /upload/**}). Non-GET requests need {@code X-AI-Diary: 1} (a
 * non-safelisted header, so other websites cannot send it without a CORS preflight, which this server never
 * approves). A present Origin must be this server's own origin; {@code Sec-Fetch-Site: cross-site} is rejected.
 * Vaadin's own requests are not touched (they carry Vaadin's CSRF token).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class UploadGuardFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-AI-Diary";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/upload") || path.startsWith("/upload/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) {
            reject(response, "ORIGIN_NOT_ALLOWED", "Cross-site requests are not allowed.");
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.trim().equalsIgnoreCase(request.getScheme() + "://" + request.getHeader("Host"))) {
            reject(response, "ORIGIN_NOT_ALLOWED", "Requests from other origins are not allowed.");
            return;
        }
        if (!SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT)) && !"1".equals(request.getHeader(HEADER))) {
            reject(response, "CSRF_HEADER_REQUIRED", "This request needs the " + HEADER + ": 1 header.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
