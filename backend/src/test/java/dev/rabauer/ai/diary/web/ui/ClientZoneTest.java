package dev.rabauer.ai.diary.web.ui;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ClientZoneTest {

    private static final ZoneId SERVER = ZoneId.of("Europe/Vienna");

    @Test
    void usesAValidBrowserZone() {
        assertThat(ClientZone.resolve("America/New_York", SERVER)).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(ClientZone.resolve("UTC", SERVER)).isEqualTo(ZoneId.of("UTC"));
        assertThat(ClientZone.resolve("America/Argentina/Buenos_Aires", SERVER)).isEqualTo(ZoneId.of("America/Argentina/Buenos_Aires"));
    }

    @Test
    void fallsBackToTheServerZone() {
        assertThat(ClientZone.resolve(null, SERVER)).isEqualTo(SERVER);
        assertThat(ClientZone.resolve("", SERVER)).isEqualTo(SERVER);
        assertThat(ClientZone.resolve("Not/AZone", SERVER)).isEqualTo(SERVER);
        assertThat(ClientZone.resolve("<script>", SERVER)).isEqualTo(SERVER);
        assertThat(ClientZone.resolve("A".repeat(200), SERVER)).isEqualTo(SERVER);
    }
}
