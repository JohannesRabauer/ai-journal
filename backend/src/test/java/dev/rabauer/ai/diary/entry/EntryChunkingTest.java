package dev.rabauer.ai.diary.entry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntryChunkingTest {

    @Test
    void shortTextIsOneChunk() {
        assertThat(EntryIndexer.chunk("One. Two.", 800)).containsExactly("One. Two.");
    }

    @Test
    void longTextIsSplitOnSentencesWithinLimit() {
        String s = "This is a sentence of moderate length. ".repeat(60);
        var chunks = EntryIndexer.chunk(s, 200);
        assertThat(chunks).hasSizeGreaterThan(5).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(200));
        assertThat(String.join(" ", chunks).replace(" ", "")).isEqualTo(s.strip().replace(" ", ""));
    }

    @Test
    void overlongSentenceIsHardSplit() {
        var chunks = EntryIndexer.chunk("x".repeat(450), 200);
        assertThat(chunks).containsExactly("x".repeat(200), "x".repeat(200), "x".repeat(50));
    }

    @Test
    void blankTextHasNoChunks() {
        assertThat(EntryIndexer.chunk("   ", 200)).isEmpty();
    }
}
