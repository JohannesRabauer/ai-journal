package dev.rabauer.ai.diary.spi;

import java.util.List;

/**
 * Optional for provider implementations: called once at startup for the SELECTED provider only. Add one
 * human-readable line per problem (e.g. "journal.stt.whisper.base-url is not a valid URL: ..."); never throw.
 * Must not perform network calls.
 */
public interface SelfValidating {
    void validateConfiguration(List<String> errors);
}
