package dev.rabauer.ai.diary.spi.vector;

import dev.rabauer.ai.diary.config.Capability;
import dev.rabauer.ai.diary.config.ConfigValidator;
import dev.rabauer.ai.diary.config.JournalConfig;
import dev.rabauer.ai.diary.config.ProviderSelection;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The shipped defaults (outside the test profile) select the real providers and pass the startup validator. */
class ProviderDefaultsTest {

    private static SmallRyeConfig config(String profile) throws IOException {
        URL url = ProviderDefaultsTest.class.getResource("/application.properties");
        SmallRyeConfigBuilder b = new SmallRyeConfigBuilder().addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(url, 250));
        if (profile != null) {
            b.withProfile(profile);
        }
        return b.build();
    }

    @Test
    void fullProfileUsesOllamaAndJdbcVectors() throws IOException {
        SmallRyeConfig c = config(null);
        assertThat(c.getValue("journal.llm.provider", String.class)).isEqualTo("ollama");
        assertThat(c.getValue("journal.embedding.provider", String.class)).isEqualTo("ollama");
        assertThat(c.getValue("journal.vector.provider", String.class)).isEqualTo("jdbc");
        List<String> errors = ConfigValidator.validate(new ConfigValidator.Input("full", List.of(
                new ProviderSelection(Capability.LLM, "ollama", Set.of("ollama", "openai", "fake"), false),
                new ProviderSelection(Capability.EMBEDDING, "ollama", Set.of("ollama", "openai", "fake"), false),
                new ProviderSelection(Capability.VECTOR, "jdbc", Set.of("jdbc", "memory"), false)),
                Optional.empty(), false));
        assertThat(errors).isEmpty();
    }

    @Test
    void liteCloudUsesOpenAiAndJdbc() throws IOException {
        SmallRyeConfig c = config("lite-cloud");
        assertThat(c.getValue("journal.llm.provider", String.class)).isEqualTo("openai");
        assertThat(c.getValue("journal.embedding.provider", String.class)).isEqualTo("openai");
        assertThat(c.getValue("journal.vector.provider", String.class)).isEqualTo("jdbc");
        List<String> errors = ConfigValidator.validate(new ConfigValidator.Input("lite-cloud", List.of(
                new ProviderSelection(Capability.LLM, "openai", Set.of("ollama", "openai", "fake"), true),
                new ProviderSelection(Capability.EMBEDDING, "openai", Set.of("ollama", "openai", "fake"), true),
                new ProviderSelection(Capability.VECTOR, "jdbc", Set.of("jdbc", "memory"), false)),
                Optional.of(JournalConfig.CLOUD_CONSENT_PHRASE), true));
        assertThat(errors).isEmpty();
    }

    @Test
    void backfillIsOnByDefaultAndOffInTests() throws IOException {
        assertThat(config(null).getValue("journal.index.backfill.enabled", Boolean.class)).isTrue();
        assertThat(config("test").getValue("journal.index.backfill.enabled", Boolean.class)).isFalse();
    }
}
