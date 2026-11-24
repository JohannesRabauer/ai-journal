package dev.rabauer.ai.diary.security;

import jakarta.enterprise.context.ApplicationScoped;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Synchronizer tokens for plain HTML form posts (the no-JavaScript path; the JavaScript path uses the
 * {@code X-AI-Diary} header). A browser "session" is a random value in the {@code diary-csrf} cookie
 * ({@code HttpOnly}, {@code SameSite=Strict}). The token embedded in a form is HMAC-SHA256(server secret, session),
 * so it is bound to that cookie and cannot be forged without the secret. The secret is per process: after a restart
 * open forms are rejected and the page has to be reloaded.
 */
@ApplicationScoped
public class CsrfTokens {

    public static final String COOKIE = "diary-csrf";
    public static final String FIELD = "_csrf";

    private static final Pattern SESSION = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final SecureRandom random = new SecureRandom();
    private final byte[] secret = new byte[32];

    public CsrfTokens() {
        random.nextBytes(secret);
    }

    /** A fresh session value for the cookie. */
    public String newSession() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public boolean validSession(String session) {
        return session != null && SESSION.matcher(session).matches();
    }

    /** The value of the hidden {@code _csrf} field for this session. */
    public String tokenFor(String session) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(session.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time check of a submitted token against the session cookie. */
    public boolean verify(String session, String token) {
        if (!validSession(session) || token == null) {
            return false;
        }
        return MessageDigest.isEqual(tokenFor(session).getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    /** Set-Cookie value. No {@code Secure} on plain HTTP (localhost); set when the request came in over TLS. */
    public String setCookie(String session, boolean secure) {
        return COOKIE + "=" + session + "; Path=/; HttpOnly; SameSite=Strict" + (secure ? "; Secure" : "");
    }
}
