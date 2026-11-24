package dev.rabauer.ai.diary.views.entries;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * "Now" and "today" in the user's browser time zone (the server, often a container, runs in UTC). Pure logic with an
 * injected clock; the Vaadin glue that learns the browser zone lives in {@link BrowserZone}.
 */
public final class ClientTime {

    private final Clock clock;
    private final ZoneId zone;

    public ClientTime(Clock clock, String browserZoneId) {
        this.clock = clock;
        this.zone = resolve(browserZoneId, clock.getZone());
    }

    /** The browser zone if it is a valid id, otherwise the fallback (server zone). */
    public static ZoneId resolve(String browserZoneId, ZoneId fallback) {
        if (browserZoneId == null || browserZoneId.isBlank()) {
            return fallback;
        }
        try {
            return ZoneId.of(browserZoneId.trim());
        } catch (DateTimeException e) {
            return fallback;
        }
    }

    public ZoneId zone() {
        return zone;
    }

    /** Local wall-clock time of the user, to the second. */
    public LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(zone)).withNano(0);
    }

    public LocalDate today() {
        return now().toLocalDate();
    }
}
