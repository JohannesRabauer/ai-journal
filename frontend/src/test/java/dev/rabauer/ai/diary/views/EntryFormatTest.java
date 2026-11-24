package dev.rabauer.ai.diary.views;

import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class EntryFormatTest {

    private static EntryDto entry(EntryStatus status) {
        return new EntryDto(1, LocalDateTime.of(2026, 11, 23, 9, 5), status, EntrySource.TEXT, null, "t", null, null, false, null, null, null);
    }

    @Test
    void finishedEntriesShowOnlyTheDate() {
        assertThat(EntryFormat.title(entry(EntryStatus.FINISHED))).isEqualTo("23.11.2026 09:05");
    }

    @Test
    void unfinishedEntriesShowTheirState() {
        assertThat(EntryFormat.title(entry(EntryStatus.TRANSCRIPTION_FAILED))).isEqualTo("23.11.2026 09:05 · Transcription failed");
    }

    @Test
    void previewUsesFirstLineAndEllipsis() {
        assertThat(EntryFormat.preview("  First line here\nsecond", 100)).isEqualTo("First line here");
        assertThat(EntryFormat.preview("abcdefghij", 5)).isEqualTo("abcd…");
        assertThat(EntryFormat.preview(null, 5)).isEmpty();
    }

    @Test
    void headingIsFirstSentenceOrShortenedFirstLine() {
        assertThat(EntryFormat.heading("Couldn't sleep past six. I keep replaying it.")).isEqualTo("Couldn't sleep past six");
        assertThat(EntryFormat.heading("Was it fine?\nsecond line")).isEqualTo("Was it fine?");
        assertThat(EntryFormat.heading("  ")).isEmpty();
        assertThat(EntryFormat.heading(null)).isEmpty();
        String longLine = "word ".repeat(40);
        String h = EntryFormat.heading(longLine);
        assertThat(h).endsWith("…").hasSizeLessThanOrEqualTo(62);
    }
}
