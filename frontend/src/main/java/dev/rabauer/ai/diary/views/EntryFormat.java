package dev.rabauer.ai.diary.views;

import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntryStatus;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Pure presentation helpers (unit-testable, no Vaadin). */
public final class EntryFormat {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.ROOT);

    private EntryFormat() {
    }

    public static String title(EntryDto e) {
        String when = e.entryTimestamp() == null ? "" : DATE.format(e.entryTimestamp());
        return e.status() == EntryStatus.FINISHED ? when : when + " · " + statusLabel(e.status());
    }

    public static String statusLabel(EntryStatus s) {
        return switch (s) {
            case DRAFT -> "Draft";
            case RECORDING -> "Recording";
            case RECORDED -> "Recorded";
            case TRANSCRIBING -> "Transcribing";
            case TRANSCRIBED -> "Ready to review";
            case TRANSCRIPTION_FAILED -> "Transcription failed";
            case FINISHED -> "Finished";
        };
    }

    /** First line, trimmed to {@code max} characters, for list previews. */
    public static String preview(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String first = text.strip().lines().findFirst().orElse("");
        return first.length() <= max ? first : first.substring(0, Math.max(0, max - 1)).stripTrailing() + "…";
    }
}
