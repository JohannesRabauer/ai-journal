package dev.rabauer.ai.diary.web.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI and Swagger UI are off unless a profile turns them on: only %dev and %test. A Quarkus test with a changed
 * build-time profile would restart the application inside the suite, so the shipped defaults are checked directly.
 */
class OpenApiProfileTest {

    private static Properties load() throws IOException {
        Properties p = new Properties();
        try (InputStream in = OpenApiProfileTest.class.getResourceAsStream("/application.properties")) {
            p.load(in);
        }
        return p;
    }

    @Test
    void offByDefaultAndOnlyEnabledForDevAndTest() throws IOException {
        Properties p = load();
        assertThat(p.getProperty("quarkus.smallrye-openapi.enable")).isEqualTo("false");
        assertThat(p.getProperty("quarkus.swagger-ui.enable")).isEqualTo("false");
        assertThat(p.getProperty("quarkus.swagger-ui.always-include")).isEqualTo("false");
        assertThat(p.getProperty("%dev.quarkus.smallrye-openapi.enable")).isEqualTo("true");
        assertThat(p.getProperty("%dev.quarkus.swagger-ui.enable")).isEqualTo("true");
        // nothing re-enables it for prod or lite-cloud
        assertThat(p.stringPropertyNames()).noneMatch(k -> (k.startsWith("%prod.") || k.startsWith("%lite-cloud."))
                && (k.contains("openapi") || k.contains("swagger")));
    }
}
