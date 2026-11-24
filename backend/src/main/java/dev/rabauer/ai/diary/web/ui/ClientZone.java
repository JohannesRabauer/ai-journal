package dev.rabauer.ai.diary.web.ui;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * The browser's time zone, sent once by {@code js/tz.js} in the {@code diary-tz} cookie. Used for "Today" and
 * "Yesterday" so they follow the user, not the server clock. Anything missing or invalid falls back to the server zone.
 */
public final class ClientZone {

    public static final String COOKIE = "diary-tz";

    private static final Pattern SHAPE = Pattern.compile("[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+){0,2}");

    private ClientZone() {
    }

    public static ZoneId resolve(String cookieValue, ZoneId fallback) {
        if (cookieValue == null || cookieValue.length() > 64 || !SHAPE.matcher(cookieValue).matches()) {
            return fallback;
        }
        try {
            return ZoneId.of(cookieValue);
        } catch (DateTimeException e) {
            return fallback;
        }
    }
}
