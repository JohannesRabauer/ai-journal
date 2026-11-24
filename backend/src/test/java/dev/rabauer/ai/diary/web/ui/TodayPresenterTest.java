package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** Cases of the Vaadin TodayGreetingTest and TodayEntriesTest, plus the timeline rows. */
class TodayPresenterTest {

    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(2026, 11, 24, hour, minute);
    }

    private static EntryDto entry(long id, LocalDateTime t, EntryStatus status, EntrySource source, String text,
                                  boolean coach) {
        return new EntryDto(id, t, status, source, null, text, null, null, coach, null, null, null);
    }

    private static EntryDto at(long id, LocalDateTime t) {
        return entry(id, t, EntryStatus.DRAFT, EntrySource.TEXT, "x", false);
    }

    @Test
    void greetingFollowsTheTimeOfDay() {
        assertThat(TodayPresenter.greetingKey(at(0, 0))).isEqualTo("today.greeting.morning");
        assertThat(TodayPresenter.greetingKey(at(11, 59))).isEqualTo("today.greeting.morning");
        assertThat(TodayPresenter.greetingKey(at(12, 0))).isEqualTo("today.greeting.afternoon");
        assertThat(TodayPresenter.greetingKey(at(17, 59))).isEqualTo("today.greeting.afternoon");
        assertThat(TodayPresenter.greetingKey(at(18, 0))).isEqualTo("today.greeting.evening");
        assertThat(TodayPresenter.greetingKey(at(23, 59))).isEqualTo("today.greeting.evening");
    }

    @Test
    void dateLineIsLocaleAware() {
        String en = TodayPresenter.dateLine(at(9, 0), Locale.ENGLISH);
        String de = TodayPresenter.dateLine(at(9, 0), Locale.GERMAN);
        assertThat(en).contains("Tuesday").contains("24").contains("November");
        assertThat(de).contains("Dienstag").contains("24").contains("November");
    }

    @Test
    void keepsOnlyTodayNewestFirst() {
        LocalDate today = LocalDate.of(2026, 5, 6);
        List<EntryDto> items = List.of(
                at(1, LocalDateTime.of(2026, 5, 5, 23, 59)),
                at(2, LocalDateTime.of(2026, 5, 6, 8, 0)),
                at(3, LocalDateTime.of(2026, 5, 6, 20, 0)),
                at(4, null));
        assertThat(TodayPresenter.todayEntries(items, today)).extracting(EntryDto::id).containsExactly(3L, 2L);
    }

    @Test
    void timelineRowShowsChipsStatusAndExcerpt() {
        Messages t = Messages.forAcceptable(List.of(Locale.ENGLISH));
        var voice = TodayPresenter.item(entry(7, at(9, 5), EntryStatus.TRANSCRIBING, EntrySource.VOICE, "", true), t);
        assertThat(voice.time()).isEqualTo("09:05");
        assertThat(voice.href()).isEqualTo("/journal/7");
        assertThat(voice.voice()).isTrue();
        assertThat(voice.coach()).isTrue();
        assertThat(voice.spinner()).isTrue();
        assertThat(voice.statusLabel()).isEqualTo("Transcribing");
        assertThat(voice.blank()).isTrue();
        assertThat(voice.excerpt()).isEqualTo("No text yet.");

        var finished = TodayPresenter.item(entry(8, at(10, 0), EntryStatus.FINISHED, EntrySource.TEXT, "Line one\nline two", false), t);
        assertThat(finished.statusLabel()).isNull();
        assertThat(finished.hasMeta()).isFalse();
        assertThat(finished.excerpt()).isEqualTo("Line one");

        var draft = TodayPresenter.item(entry(9, at(10, 30), EntryStatus.DRAFT, EntrySource.TEXT, "Hello", false), t);
        assertThat(draft.statusLabel()).isEqualTo("Draft");
        assertThat(draft.spinner()).isFalse();
    }

    @Test
    void germanTextsAreUsed() {
        Messages t = Messages.forAcceptable(List.of(Locale.GERMAN));
        var item = TodayPresenter.item(entry(1, at(8, 0), EntryStatus.DRAFT, EntrySource.TEXT, " ", false), t);
        assertThat(item.excerpt()).isEqualTo(t.get("today.entry.noText")).doesNotContain("??");
        assertThat(item.statusLabel()).doesNotContain("??");
    }
}
