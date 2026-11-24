package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.insights.WeeklyReportDto;
import dev.rabauer.ai.diary.api.insights.WeeklyReportSummaryDto;
import dev.rabauer.ai.diary.web.ApiException;
import jakarta.ws.rs.core.Response;
import dev.rabauer.ai.diary.web.ui.WeeklyPresenter.EditDraft;
import dev.rabauer.ai.diary.web.ui.WeeklyPresenter.Week;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyPresenterTest {

    private static WeeklyReportDto report() {
        return new WeeklyReportDto(5, 2031, 10, Language.EN,
                List.of(new WeeklyReportDto.Theme("Budget", "The plan came up twice.", List.of(1L, 2L)),
                        new WeeklyReportDto.Theme("Walks", "Along the river.", List.of(2L))),
                List.of(new WeeklyReportDto.Quote("I felt unsure", 1L), new WeeklyReportDto.Quote("It is quiet here", 2L)),
                List.of("How the numbers add up"),
                new WeeklyReportDto.Learned(3, 5, List.of("What is X?")),
                List.of("Entries on Monday and Wednesday."),
                "What would you like to look at again?",
                Instant.parse("2031-03-10T08:00:00Z"), null);
    }

    // ------------------------------------------------------------------ weeks

    @Test
    void weekOfDateUsesIsoRules() {
        assertThat(Week.of(LocalDate.of(2031, 3, 9))).isEqualTo(new Week(2031, 10));   // Sunday
        assertThat(Week.of(LocalDate.of(2031, 3, 10))).isEqualTo(new Week(2031, 11));  // Monday
        assertThat(Week.of(LocalDate.of(2021, 1, 3))).isEqualTo(new Week(2020, 53));
        assertThat(Week.of(LocalDate.of(2021, 1, 4))).isEqualTo(new Week(2021, 1));
        assertThat(Week.of(LocalDate.of(2024, 12, 30))).isEqualTo(new Week(2025, 1));
        assertThat(new Week(2031, 10).monday()).isEqualTo(LocalDate.of(2031, 3, 3));
        assertThat(new Week(2031, 10).sunday()).isEqualTo(LocalDate.of(2031, 3, 9));
        assertThat(new Week(2020, 53).sunday()).isEqualTo(LocalDate.of(2021, 1, 3));
    }

    @Test
    void defaultIsTheLastCompletedWeekAndThisWeekIsAnOption() {
        // Wednesday 2031-03-12 is in week 11; the last completed week is 10
        LocalDate wednesday = LocalDate.of(2031, 3, 12);
        assertThat(WeeklyPresenter.lastCompleted(wednesday)).isEqualTo(new Week(2031, 10));
        assertThat(WeeklyPresenter.thisWeek(wednesday)).isEqualTo(new Week(2031, 11));
        // Monday starts a new week: the week that just ended is complete
        assertThat(WeeklyPresenter.lastCompleted(LocalDate.of(2031, 3, 10))).isEqualTo(new Week(2031, 10));
        // Sunday is still part of its own week
        assertThat(WeeklyPresenter.lastCompleted(LocalDate.of(2031, 3, 9))).isEqualTo(new Week(2031, 9));
        // across the turn of the year
        assertThat(WeeklyPresenter.lastCompleted(LocalDate.of(2021, 1, 5))).isEqualTo(new Week(2020, 53));
    }

    @Test
    void pickerListsThisWeekFirstThenCompletedWeeksAndOldReportWeeks() {
        List<Week> weeks = WeeklyPresenter.pickerWeeks(LocalDate.of(2031, 3, 12), List.of(new Week(2030, 3), new Week(2031, 8)));
        assertThat(weeks.get(0)).isEqualTo(new Week(2031, 11));
        assertThat(weeks.get(1)).isEqualTo(new Week(2031, 10));
        assertThat(weeks).hasSize(1 + WeeklyPresenter.PICKER_WEEKS + 1).doesNotHaveDuplicates()
                .contains(new Week(2030, 3)).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void rangeShowsMondayToSunday() {
        String range = WeeklyPresenter.range(new Week(2031, 10), Locale.GERMAN);
        assertThat(range).contains("03.03.2031").contains("09.03.2031");
        assertThat(WeeklyPresenter.entryDate(LocalDateTime.of(2031, 3, 3, 10, 0), Locale.ENGLISH)).contains("Mon").contains("3");
        assertThat(WeeklyPresenter.entryDate(null, Locale.ENGLISH)).isNull();
        assertThat(WeeklyPresenter.dateTime(Instant.parse("2031-03-10T08:00:00Z"), ZoneId.of("Europe/Berlin"), Locale.GERMAN))
                .contains("10.03.2031").contains("09:00");
        assertThat(WeeklyPresenter.dateTime(null, ZoneId.of("UTC"), Locale.ENGLISH)).isEmpty();
    }

    @Test
    void summaryLookupByWeek() {
        var list = List.of(new WeeklyReportSummaryDto(1, 2031, 10, Instant.EPOCH), new WeeklyReportSummaryDto(2, 2031, 9, Instant.EPOCH));
        assertThat(WeeklyPresenter.summaryOf(new Week(2031, 9), list)).get().extracting(WeeklyReportSummaryDto::id).isEqualTo(2L);
        assertThat(WeeklyPresenter.summaryOf(new Week(2031, 8), list)).isEmpty();
        assertThat(WeeklyPresenter.summaryOf(new Week(2031, 8), null)).isEmpty();
        assertThat(WeeklyPresenter.weekOf(list.get(0))).isEqualTo(new Week(2031, 10));
    }

    // ------------------------------------------------------------------ errors

    private static ApiException failure(int status, String code) {
        return new ApiException(Response.Status.fromStatusCode(status), code, "message");
    }

    @Test
    void errorsAreCalmAndRetryOnlyWhenRepeatingCanHelp() {
        assertThat(WeeklyPresenter.errorFor(new IllegalStateException("x"))).isEqualTo(new WeeklyPresenter.ErrorInfo("weekly.error.unreachable", true, false));
        assertThat(WeeklyPresenter.errorFor(failure(502, "LLM_OUTPUT_INVALID"))).isEqualTo(new WeeklyPresenter.ErrorInfo("weekly.error.invalid", true, false));
        assertThat(WeeklyPresenter.errorFor(failure(503, "PROVIDER_UNREACHABLE"))).isEqualTo(new WeeklyPresenter.ErrorInfo("weekly.error.provider", true, false));
        assertThat(WeeklyPresenter.errorFor(failure(409, "NO_ENTRIES_IN_WEEK"))).isEqualTo(new WeeklyPresenter.ErrorInfo("weekly.error.noEntries", false, false));
        assertThat(WeeklyPresenter.errorFor(failure(400, "INVALID_WEEK")).messageKey()).isEqualTo("weekly.error.badWeek");
        assertThat(WeeklyPresenter.errorFor(failure(400, "INVALID_REPORT")).messageKey()).isEqualTo("weekly.error.invalidEdit");
        assertThat(WeeklyPresenter.errorFor(failure(404, "NOT_FOUND"))).isEqualTo(new WeeklyPresenter.ErrorInfo("weekly.error.notFound", false, true));
        assertThat(WeeklyPresenter.errorFor(failure(501, "NOT_IMPLEMENTED")).retryable()).isFalse();
        assertThat(WeeklyPresenter.errorFor(failure(500, "X")).retryable()).isTrue();
    }

    @Test
    void everyErrorKeyExistsInBothLanguages() {
        MessageCatalog catalog = MessageCatalog.fromClasspath(getClass().getClassLoader());
        for (RuntimeException e : List.of(new IllegalStateException(), failure(502, "LLM_OUTPUT_INVALID"), failure(503, "PROVIDER_ERROR"),
                failure(409, "NO_ENTRIES_IN_WEEK"), failure(400, "INVALID_WEEK"), failure(400, "INVALID_REPORT"), failure(404, "NOT_FOUND"),
                failure(501, "X"))) {
            String key = WeeklyPresenter.errorFor(e).messageKey();
            assertThat(catalog.get(Locale.ENGLISH, key)).as(key).isNotEqualTo(key);
            assertThat(catalog.get(Locale.GERMAN, key)).as(key).isNotEqualTo(key).isNotEqualTo(catalog.get(Locale.ENGLISH, key));
        }
    }

    // ------------------------------------------------------------------ sections and wording

    @Test
    void sectionHeadingsAreExactlyThoseOfSpecSection8() {
        MessageCatalog catalog = MessageCatalog.fromClasspath(getClass().getClassLoader());
        assertThat(List.of("themes", "quotes", "threads", "learned", "patterns", "question").stream()
                .map(k -> catalog.get(Locale.ENGLISH, "weekly.section." + k)).toList())
                .containsExactly("Recurring themes", "In your own words", "Open threads", "What you learned", "Noticed patterns",
                        "A question for next week");
        assertThat(List.of("themes", "quotes", "threads", "learned", "patterns", "question").stream()
                .map(k -> catalog.get(Locale.GERMAN, "weekly.section." + k)).toList())
                .containsExactly("Wiederkehrende Themen", "In deinen eigenen Worten", "Offene Fäden", "Was du gelernt hast",
                        "Beobachtete Muster", "Eine Frage für nächste Woche");
    }

    @Test
    void learnedIsPlainCountsWithoutGrading() {
        MessageCatalog catalog = MessageCatalog.fromClasspath(getClass().getClassLoader());
        assertThat(catalog.get(Locale.ENGLISH, "weekly.learned.summary", 3, 5)).isEqualTo("3 new memory items, 5 reviews");
        assertThat(catalog.get(Locale.ENGLISH, "weekly.learned.summary", 1, 1)).isEqualTo("1 new memory item, 1 review");
        assertThat(catalog.get(Locale.ENGLISH, "weekly.learned.summary", 0, 2)).isEqualTo("No new memory items, 2 reviews");
        assertThat(catalog.get(Locale.GERMAN, "weekly.learned.summary", 3, 5)).contains("3 neue").contains("5 Wiederholungen");
        assertThat(WeeklyPresenter.hasLearned(new WeeklyReportDto.Learned(0, 0, List.of()))).isFalse();
        assertThat(WeeklyPresenter.hasLearned(new WeeklyReportDto.Learned(0, 1, List.of()))).isTrue();
        assertThat(WeeklyPresenter.hasLearned(new WeeklyReportDto.Learned(0, 0, List.of("q?")))).isTrue();
        assertThat(WeeklyPresenter.hasLearned(null)).isFalse();
    }

    @Test
    void textsContainNoScoreStreakOrProductivityLanguage() {
        MessageCatalog catalog = MessageCatalog.fromClasspath(getClass().getClassLoader());
        for (Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
            for (String key : List.of("intro", "empty.text", "creating", "learned.none", "edit.help", "regenerate.text")) {
                String text = catalog.get(locale, "weekly." + key).toLowerCase(locale);
                assertThat(text).as(key).doesNotContain("streak", "score", "productiv", "punkte", "%", "serie");
            }
        }
    }

    @Test
    void referencedEntriesAreUniqueInOrder() {
        assertThat(WeeklyPresenter.referencedEntries(report())).containsExactly(1L, 2L);
    }

    // ------------------------------------------------------------------ edit draft

    @Test
    void untouchedDraftReproducesTheReport() {
        WeeklyReportDto r = report();
        assertThat(new EditDraft(r).toReport()).isEqualTo(r);
    }

    @Test
    void editsAreStrippedBlankItemsDroppedAndReferencesKept() {
        WeeklyReportDto r = report();
        EditDraft draft = new EditDraft(r);
        draft.themes.get(0).title = "  My own title  ";
        draft.themes.get(0).description = "burnout 100%";
        draft.themes.remove(1);
        EditDraft.ThemeRow added = draft.addTheme();
        added.title = "New theme";
        draft.addTheme(); // left blank: dropped
        draft.quotes.get(1).text = "   ";
        draft.openThreads.add("   ");
        draft.openThreads.add(" Call Anna ");
        draft.patterns.clear();
        draft.itemsCreated = -4;
        draft.reviewsDone = 7;
        draft.question = "  Anything?  ";

        WeeklyReportDto out = draft.toReport();

        assertThat(out.id()).isEqualTo(5);
        assertThat(out.isoWeek()).isEqualTo(10);
        assertThat(out.language()).isEqualTo(Language.EN);
        assertThat(out.generatedAt()).isEqualTo(r.generatedAt());
        assertThat(out.recurringThemes()).extracting(WeeklyReportDto.Theme::title).containsExactly("My own title", "New theme");
        assertThat(out.recurringThemes().get(0).description()).isEqualTo("burnout 100%");
        assertThat(out.recurringThemes().get(0).entryIds()).containsExactly(1L, 2L);
        assertThat(out.recurringThemes().get(1).entryIds()).isEmpty();
        assertThat(out.inYourOwnWords()).hasSize(1);
        assertThat(out.inYourOwnWords().get(0).entryId()).isEqualTo(1L);
        assertThat(out.openThreads()).containsExactly("How the numbers add up", "Call Anna");
        assertThat(out.noticedPatterns()).isEmpty();
        assertThat(out.whatYouLearned()).isEqualTo(new WeeklyReportDto.Learned(0, 7, List.of("What is X?")));
        assertThat(out.questionForNextWeek()).isEqualTo("Anything?");
    }

    @Test
    void draftToleratesMissingSections() {
        WeeklyReportDto sparse = new WeeklyReportDto(1, 2031, 10, Language.DE, null, null, null, null, null, null, Instant.EPOCH, null);
        WeeklyReportDto out = new EditDraft(sparse).toReport();
        assertThat(out.recurringThemes()).isEmpty();
        assertThat(out.inYourOwnWords()).isEmpty();
        assertThat(out.whatYouLearned()).isEqualTo(new WeeklyReportDto.Learned(0, 0, List.of()));
        assertThat(out.questionForNextWeek()).isEmpty();
    }
}
