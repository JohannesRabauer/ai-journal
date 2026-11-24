package dev.rabauer.ai.diary.views.entries;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ClientTimeTest {

    // 2026-03-04 23:46:30 UTC is already 00:46 on 5 March in Berlin (CET).
    private static final Clock UTC_CLOCK = Clock.fixed(Instant.parse("2026-03-04T23:46:30.123Z"), ZoneOffset.UTC);

    @Test
    void usesBrowserZoneNotServerZone() {
        var t = new ClientTime(UTC_CLOCK, "Europe/Berlin");
        assertThat(t.now()).isEqualTo(LocalDateTime.of(2026, 3, 5, 0, 46, 30));
        assertThat(t.today()).isEqualTo(LocalDate.of(2026, 3, 5));
    }

    @Test
    void westOfUtcGoesBackADay() {
        assertThat(new ClientTime(UTC_CLOCK, "America/Los_Angeles").now()).isEqualTo(LocalDateTime.of(2026, 3, 4, 15, 46, 30));
    }

    @Test
    void fallsBackToServerZoneWhenUnknownOrInvalid() {
        assertThat(new ClientTime(UTC_CLOCK, null).zone()).isEqualTo(ZoneOffset.UTC);
        assertThat(new ClientTime(UTC_CLOCK, " ").zone()).isEqualTo(ZoneOffset.UTC);
        assertThat(new ClientTime(UTC_CLOCK, "Mars/Olympus").zone()).isEqualTo(ZoneOffset.UTC);
        assertThat(ClientTime.resolve("Europe/Berlin", ZoneId.of("UTC"))).isEqualTo(ZoneId.of("Europe/Berlin"));
    }
}
