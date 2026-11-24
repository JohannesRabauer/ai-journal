package dev.rabauer.ai.diary.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CsrfTokensTest {

    private final CsrfTokens tokens = new CsrfTokens();

    @Test
    void tokenFitsOnlyItsOwnSession() {
        String a = tokens.newSession();
        String b = tokens.newSession();
        assertThat(a).isNotEqualTo(b);
        assertThat(tokens.validSession(a)).isTrue();
        assertThat(tokens.verify(a, tokens.tokenFor(a))).isTrue();
        assertThat(tokens.verify(a, tokens.tokenFor(b))).isFalse();
        assertThat(tokens.verify(a, a)).isFalse();
    }

    @Test
    void malformedInputIsNeverValid() {
        String a = tokens.newSession();
        assertThat(tokens.verify(a, null)).isFalse();
        assertThat(tokens.verify(null, "x")).isFalse();
        assertThat(tokens.verify("short", tokens.tokenFor(a))).isFalse();
        assertThat(tokens.validSession("a; b")).isFalse();
    }

    @Test
    void anotherProcessSecretDoesNotVerify() {
        String a = tokens.newSession();
        assertThat(new CsrfTokens().verify(a, tokens.tokenFor(a))).isFalse();
    }

    @Test
    void cookieIsHttpOnlySameSiteStrictAndSecureOnlyOverTls() {
        assertThat(tokens.setCookie("v", false)).isEqualTo("diary-csrf=v; Path=/; HttpOnly; SameSite=Strict");
        assertThat(tokens.setCookie("v", true)).endsWith("; Secure");
    }

    @Test
    void ownOriginIsTheHostTheBrowserUsed() {
        Set<String> hosts = HostAllowlist.hosts(List.of("pi.local"));
        assertThat(RequestGuardFilter.sameOrigin("http://127.0.0.1:8080", "127.0.0.1:8080", hosts)).isTrue();
        assertThat(RequestGuardFilter.sameOrigin("http://localhost:8080", "localhost:8080", hosts)).isTrue();
        assertThat(RequestGuardFilter.sameOrigin("http://PI.local:8080", "pi.local:8080", hosts)).isTrue();
        assertThat(RequestGuardFilter.sameOrigin("https://pi.local", "pi.local", hosts)).isTrue();
        // another port, another host, another scheme, no origin
        assertThat(RequestGuardFilter.sameOrigin("http://localhost:9999", "localhost:8080", hosts)).isFalse();
        assertThat(RequestGuardFilter.sameOrigin("http://localhost:8080", "127.0.0.1:8080", hosts)).isFalse();
        assertThat(RequestGuardFilter.sameOrigin("ftp://localhost:8080", "localhost:8080", hosts)).isFalse();
        assertThat(RequestGuardFilter.sameOrigin("null", "localhost:8080", hosts)).isFalse();
        assertThat(RequestGuardFilter.sameOrigin("http://localhost:8080", null, hosts)).isFalse();
        // a host that is not on the allowlist is never "own", even when Origin and Host agree
        assertThat(RequestGuardFilter.sameOrigin("http://evil.example", "evil.example", hosts)).isFalse();
        assertThat(RequestGuardFilter.sameOrigin("http://localhost:8080/path", "localhost:8080", hosts)).isFalse();
    }
}
