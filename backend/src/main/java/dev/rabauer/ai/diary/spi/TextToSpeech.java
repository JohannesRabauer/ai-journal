package dev.rabauer.ai.diary.spi;

import dev.rabauer.ai.diary.api.common.Language;

import java.util.List;

/**
 * Text to speech. Implementations: {@code piper} (local), {@code openai} (lite-cloud only), {@code none}, {@code fake}.
 */
public interface TextToSpeech {

    /** @throws ProviderException on failure; callers fall back to silent text (SPEC section 9) */
    Audio synthesize(Request request);

    List<Voice> voices();

    /** @param voice provider voice id, null = provider default for the language */
    record Request(String text, Language language, String voice) {
    }

    record Audio(byte[] data, String contentType) {
    }

    record Voice(String id, Language language, String label) {
    }
}
