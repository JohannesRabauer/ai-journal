package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.insights.WeeklyReportDto;
import dev.rabauer.ai.diary.web.ui.WeeklyPresenter.EditDraft;
import dev.rabauer.ai.diary.web.ui.WeeklyPresenter.Week;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The parts of the week page that the Vaadin presenter did not have: week codes in addresses and the posted edit form. */
class WeeklyPresenterFormTest {

    private static WeeklyReportDto report() {
        return new WeeklyReportDto(5, 2031, 10, Language.EN,
                List.of(new WeeklyReportDto.Theme("Budget", "Came up twice.", List.of(1L, 2L))),
                List.of(new WeeklyReportDto.Quote("I felt unsure", 1L), new WeeklyReportDto.Quote("It is quiet here", 2L)),
                List.of("How the numbers add up"), new WeeklyReportDto.Learned(3, 5, List.of("What is X?")),
                List.of("Entries on Monday."), "What next?", Instant.parse("2031-03-10T08:00:00Z"), null);
    }

    @Test
    void weekCodesRoundTripAndRejectNonsense() {
        assertThat(new Week(2026, 5).code()).isEqualTo("2026-W05");
        assertThat(Week.parse("2026-W05")).contains(new Week(2026, 5));
        assertThat(Week.parse("2020-W53")).contains(new Week(2020, 53));
        assertThat(Week.parse("2021-W53")).isEmpty(); // 2021 has only 52 ISO weeks
        assertThat(Week.parse("2026-W00")).isEmpty();
        assertThat(Week.parse("2026-5")).isEmpty();
        assertThat(Week.parse("<b>")).isEmpty();
        assertThat(Week.parse(null)).isEmpty();
        assertThat(Week.parse("1800-W10")).isEmpty();
    }

    @Test
    void postedFormReplacesTheSectionsAndKeepsEntryReferences() {
        EditDraft draft = new EditDraft(report());
        draft.applyForm(List.of("Budget", "Walks", ""), List.of("Edited.", "Along the river.", ""), List.of("1,2", "2", ""),
                List.of("I felt unsure", "It is quiet here"), List.of("1", "2"),
                List.of("How the numbers add up", ""), List.of("Own pattern", ""), 4, 6, "My question");

        WeeklyReportDto out = draft.toReport();

        assertThat(out.recurringThemes()).extracting(WeeklyReportDto.Theme::title).containsExactly("Budget", "Walks"); // blank row dropped
        assertThat(out.recurringThemes().get(0).description()).isEqualTo("Edited.");
        assertThat(out.recurringThemes().get(0).entryIds()).containsExactly(1L, 2L);
        assertThat(out.inYourOwnWords()).extracting(WeeklyReportDto.Quote::entryId).containsExactly(1L, 2L);
        assertThat(out.openThreads()).containsExactly("How the numbers add up");
        assertThat(out.noticedPatterns()).containsExactly("Own pattern");
        assertThat(out.whatYouLearned().itemsCreated()).isEqualTo(4);
        assertThat(out.whatYouLearned().reviewsDone()).isEqualTo(6);
        assertThat(out.whatYouLearned().itemPrompts()).containsExactly("What is X?");
        assertThat(out.questionForNextWeek()).isEqualTo("My question");
        assertThat(out.id()).isEqualTo(5);
        assertThat(out.generatedAt()).isEqualTo(Instant.parse("2031-03-10T08:00:00Z"));
    }

    @Test
    void emptyQuoteRowsAndGarbageIdsAreDroppedNotTrusted() {
        EditDraft draft = new EditDraft(report());
        draft.applyForm(List.of("T"), List.of("d"), List.of("x, -3, 0, 7, 7"),
                List.of("kept", "no entry", "blank"), List.of("1", "", "2"), null, null, null, null, null);

        WeeklyReportDto out = draft.toReport();

        assertThat(out.recurringThemes().get(0).entryIds()).containsExactly(7L);
        assertThat(out.inYourOwnWords()).extracting(WeeklyReportDto.Quote::text).containsExactly("kept", "blank");
        assertThat(out.openThreads()).isEmpty();
        assertThat(out.whatYouLearned().itemsCreated()).isEqualTo(3); // counts not posted: unchanged
        assertThat(out.questionForNextWeek()).isEmpty();
    }
}
