package dev.rabauer.ai.diary.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Rejects requests whose Host header is not a loopback name or listed in {@code JOURNAL_ALLOWED_HOSTS}
 * (DNS rebinding protection). Runs before Vaadin's servlet; answers 421 without details.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HostAllowlistFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(HostAllowlistFilter.class);

    private final Set<String> hosts;

    public HostAllowlistFilter(@Value("${journal.allowed-hosts:}") List<String> extraHosts) {
        this.hosts = HostAllowlist.hosts(extraHosts);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!HostAllowlist.allowed(request.getHeader("Host"), hosts)) {
            LOG.warn("Request rejected: unexpected Host header");
            response.setStatus(421);
            return;
        }
        chain.doFilter(request, response);
    }
}
