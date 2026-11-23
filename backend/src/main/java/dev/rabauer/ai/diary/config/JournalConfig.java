package dev.rabauer.ai.diary.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/**
 * Root application configuration. Provider-specific settings live under {@code journal.<capability>.<provider>.*}
 * and are read by the provider implementation itself (with defaults, so unused providers never fail startup).
 */
@ConfigMapping(prefix = "journal")
public interface JournalConfig {

    String PROFILE_FULL = "full";
    String PROFILE_LITE_CLOUD = "lite-cloud";
    String CLOUD_CONSENT_PHRASE = "I_UNDERSTAND_DATA_LEAVES_THIS_DEVICE";

    /** {@code full} (profiles A and B) or {@code lite-cloud} (profile C). */
    @WithDefault(PROFILE_FULL)
    String profile();

    Cloud cloud();

    OpenAi openai();

    Provider stt();

    Provider tts();

    Provider llm();

    Provider embedding();

    Provider vector();

    Provider audio();

    Provider scheduler();

    interface Provider {
        String provider();
    }

    interface Cloud {
        /** Must equal {@link #CLOUD_CONSENT_PHRASE} in lite-cloud (env JOURNAL_CLOUD_CONSENT). */
        Optional<String> consent();
    }

    interface OpenAi {
        /** env OPENAI_API_KEY */
        Optional<String> apiKey();

        @WithDefault("https://api.openai.com/v1")
        String baseUrl();
    }

    default String providerFor(Capability c) {
        return switch (c) {
            case STT -> stt().provider();
            case TTS -> tts().provider();
            case LLM -> llm().provider();
            case EMBEDDING -> embedding().provider();
            case VECTOR -> vector().provider();
            case AUDIO -> audio().provider();
            case SCHEDULER -> scheduler().provider();
        };
    }
}
