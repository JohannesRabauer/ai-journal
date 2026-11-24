package dev.rabauer.ai.diary.security;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveHostTest {

    @Test
    void usesTheHostHeaderWhenPresent() {
        assertThat(RequestGuardFilter.effectiveHost("backend:8080", URI.create("http://evil.example/x"))).isEqualTo("backend:8080");
    }

    @Test
    void fallsBackToTheUriAuthorityForHttp2() {
        assertThat(RequestGuardFilter.effectiveHost(null, URI.create("http://backend:8080/api/entries"))).isEqualTo("backend:8080");
    }

    @Test
    void nothingToGoOnStaysNull() {
        assertThat(RequestGuardFilter.effectiveHost(null, null)).isNull();
    }
}
