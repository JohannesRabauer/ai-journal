package dev.rabauer.ai.diary.config;

import dev.rabauer.ai.diary.spi.AudioStorage;
import dev.rabauer.ai.diary.spi.EmbeddingGateway;
import dev.rabauer.ai.diary.spi.LlmGateway;
import dev.rabauer.ai.diary.spi.ReviewScheduler;
import dev.rabauer.ai.diary.spi.SpeechToText;
import dev.rabauer.ai.diary.spi.TextToSpeech;
import dev.rabauer.ai.diary.spi.VectorStore;

/** Replaceable capabilities, each selected by {@code journal.<key>.provider}. */
public enum Capability {
    STT("stt", SpeechToText.class),
    TTS("tts", TextToSpeech.class),
    LLM("llm", LlmGateway.class),
    EMBEDDING("embedding", EmbeddingGateway.class),
    VECTOR("vector", VectorStore.class),
    AUDIO("audio", AudioStorage.class),
    SCHEDULER("scheduler", ReviewScheduler.class);

    private final String key;
    private final Class<?> type;

    Capability(String key, Class<?> type) {
        this.key = key;
        this.type = type;
    }

    public String key() {
        return key;
    }

    public Class<?> type() {
        return type;
    }

    public String property() {
        return "journal." + key + ".provider";
    }
}
