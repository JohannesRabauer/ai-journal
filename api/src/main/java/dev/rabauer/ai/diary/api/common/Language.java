package dev.rabauer.ai.diary.api.common;

/** Languages for entries, transcription, coach and TTS. {@code AUTO} is only valid as a request value. */
public enum Language {
    AUTO, DE, EN;

    public String code() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Language fromCode(String code) {
        if (code == null || code.isBlank()) {
            return AUTO;
        }
        return valueOf(code.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
