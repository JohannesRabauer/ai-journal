package dev.rabauer.ai.diary.spi;

import dev.rabauer.ai.diary.api.common.Language;

import java.time.Duration;

/**
 * Speech to text. Implementations: {@code whisper} (local faster-whisper, OpenAI-compatible HTTP API),
 * {@code openai} (lite-cloud only), {@code fake}.
 */
public interface SpeechToText {

    /**
     * Blocking; called from a worker thread, never from the event loop.
     *
     * @throws ProviderException on any provider failure (never returns null)
     */
    Result transcribe(Request request);

    /** @param language {@link Language#AUTO} lets the provider detect de/en */
    record Request(AudioSource audio, Language language) {
    }

    /** @param detectedLanguage DE or EN, or AUTO if the provider could not tell */
    record Result(String text, Language detectedLanguage, Duration audioDuration) {
    }
}
