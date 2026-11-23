package dev.rabauer.ai.diary.support;

import dev.rabauer.ai.diary.spi.ProviderName;
import dev.rabauer.ai.diary.spi.fake.FakeLlmGateway;
import dev.rabauer.ai.diary.spi.fake.FakeSpeechToText;
import dev.rabauer.ai.diary.spi.fake.FakeTextToSpeech;
import dev.rabauer.ai.diary.spi.fake.InMemoryAudioStorage;
import dev.rabauer.ai.diary.spi.fake.InMemoryVectorStore;
import io.quarkus.arc.Arc;

/** Access to the fake providers inside {@code @QuarkusTest}s. */
public final class Fakes {

    private Fakes() {
    }

    private static <T> T get(Class<T> type, String name) {
        return Arc.container().select(type, new ProviderName.Literal(name)).get();
    }

    public static FakeLlmGateway llm() {
        return get(FakeLlmGateway.class, "fake");
    }

    public static FakeSpeechToText stt() {
        return get(FakeSpeechToText.class, "fake");
    }

    public static FakeTextToSpeech tts() {
        return get(FakeTextToSpeech.class, "fake");
    }

    public static InMemoryVectorStore vectors() {
        return get(InMemoryVectorStore.class, "memory");
    }

    public static InMemoryAudioStorage audio() {
        return get(InMemoryAudioStorage.class, "memory");
    }

    /** Call in {@code @BeforeEach}. */
    public static void reset() {
        llm().reset();
        stt().reset();
        vectors().deleteAll();
        audio().deleteAll();
    }
}
