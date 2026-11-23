package dev.rabauer.ai.diary.spi.llm;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Optional;

/**
 * Registers the provider and backfill keys below {@code journal.*} with the configuration system.
 *
 * <p>{@code JournalConfig} is a {@code @ConfigMapping(prefix = "journal")}, so SmallRye rejects any {@code journal.*}
 * property in a properties file that is not part of some mapping ("does not map to any root"). The gateways and
 * {@code EntryBackfill} read their values with {@code @ConfigProperty} (with defaults, so an unused provider never
 * breaks startup); this interface only makes the same keys known, so operators may also set them in
 * {@code application.properties} (environment variables always worked). Keep both in sync; defaults live in the
 * consumers.
 */
@ConfigMapping(prefix = "journal")
public interface W1eConfigKeys {

    Llm llm();

    Embedding embedding();

    Index index();

    interface Llm {
        OllamaLlm ollama();

        OpenAiModel openai();
    }

    interface OllamaLlm {
        @WithDefault("http://localhost:11434")
        String baseUrl();

        @WithDefault("qwen3:1.7b")
        String model();

        @WithDefault("5m")
        Duration timeout();

        Optional<Double> temperature();
    }

    interface OpenAiModel {
        @WithDefault("gpt-4.1-mini")
        String model();

        @WithDefault("2m")
        Duration timeout();
    }

    interface Embedding {
        OllamaEmbedding ollama();

        OpenAiEmbedding openai();
    }

    interface OllamaEmbedding {
        @WithDefault("http://localhost:11434")
        String baseUrl();

        @WithDefault("qwen3-embedding:0.6b")
        String model();

        @WithDefault("2m")
        Duration timeout();
    }

    interface OpenAiEmbedding {
        @WithDefault("text-embedding-3-small")
        String model();

        @WithDefault("2m")
        Duration timeout();
    }

    interface Index {
        Backfill backfill();
    }

    interface Backfill {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("10s")
        Duration initialDelay();

        @WithDefault("15m")
        Duration interval();

        @WithDefault("250ms")
        Duration pause();
    }
}
