package dev.rabauer.ai.diary.gym;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GymExcerptsTest {

    private static final String ENTRY = "Today the meeting went badly.\n\nI was sure that   everyone saw it as my fault. "
            + "She said “let’s talk later” and left.";

    @Test
    void acceptsVerbatimPassagesAndReturnsTheEntrysOwnWording() {
        assertThat(GymExcerpts.findVerbatim(ENTRY, "I was sure that everyone saw it as my fault."))
                .contains("I was sure that   everyone saw it as my fault.");
        assertThat(GymExcerpts.findVerbatim(ENTRY, "i was SURE that everyone saw it as my fault")).isPresent();
    }

    @Test
    void ignoresQuoteStyleAndWhitespace() {
        assertThat(GymExcerpts.findVerbatim(ENTRY, "She said \"let's talk later\" and left.")).isPresent();
        assertThat(GymExcerpts.findVerbatim(ENTRY, "meeting went badly. I was sure")).isPresent();
    }

    @Test
    void rejectsInventedShortenedOrEmptyExcerpts() {
        assertThat(GymExcerpts.findVerbatim(ENTRY, "Everybody blamed me for the meeting.")).isEmpty();
        assertThat(GymExcerpts.findVerbatim(ENTRY, "I was sure that ... my fault")).isEmpty();
        assertThat(GymExcerpts.findVerbatim(ENTRY, "meeting")).as("too short").isEmpty();
        assertThat(GymExcerpts.findVerbatim(ENTRY, " ")).isEmpty();
        assertThat(GymExcerpts.findVerbatim(ENTRY, null)).isEmpty();
        assertThat(GymExcerpts.findVerbatim(null, "I was sure that")).isEmpty();
    }

    @Test
    void rejectsPassagesLongerThanTheLimit() {
        String longEntry = ("word ".repeat(120)).strip();
        assertThat(GymExcerpts.findVerbatim(longEntry, longEntry)).isEmpty();
    }
}
