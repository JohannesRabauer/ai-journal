package dev.rabauer.ai.diary.views.entries;

import dev.rabauer.ai.diary.views.entries.AutosavePresenter.Action;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutosavePresenterTest {

    @Test
    void nothingIsCreatedForBlankText() {
        var p = AutosavePresenter.forNewEntry();
        assertThat(p.decide(null)).isEqualTo(Action.NONE);
        assertThat(p.decide("")).isEqualTo(Action.NONE);
        assertThat(p.decide("  \n ")).isEqualTo(Action.NONE);
        assertThat(p.entryId()).isNull();
    }

    @Test
    void entryIsCreatedOnFirstText_thenUpdatedOnlyWhenChanged() {
        var p = AutosavePresenter.forNewEntry();
        assertThat(p.decide("H")).isEqualTo(Action.CREATE);
        p.saved(5, "H");
        assertThat(p.entryId()).isEqualTo(5L);
        assertThat(p.decide("H")).isEqualTo(Action.NONE);
        assertThat(p.decide("Hello")).isEqualTo(Action.UPDATE);
        p.saved(5, "Hello");
        assertThat(p.decide("Hello")).isEqualTo(Action.NONE);
    }

    @Test
    void failedSaveKeepsDecidingTheSameUntilItSucceeds() {
        var p = AutosavePresenter.forNewEntry();
        assertThat(p.decide("a")).isEqualTo(Action.CREATE);
        // create failed: saved() is not called
        assertThat(p.decide("ab")).isEqualTo(Action.CREATE);
        p.saved(1, "ab");
        assertThat(p.decide("abc")).isEqualTo(Action.UPDATE);
        // update failed: still dirty
        assertThat(p.isDirty("abc")).isTrue();
    }

    @Test
    void clearingTextOfAnExistingEntryIsSaved() {
        var p = AutosavePresenter.forExisting(3, "text");
        assertThat(p.decide("")).isEqualTo(Action.UPDATE);
    }

    @Test
    void existingEntryStartsClean() {
        var p = AutosavePresenter.forExisting(3, "text");
        assertThat(p.decide("text")).isEqualTo(Action.NONE);
        assertThat(p.decide("text!")).isEqualTo(Action.UPDATE);
    }

    @Test
    void resetStartsANewEntry() {
        var p = AutosavePresenter.forExisting(3, "text");
        p.reset();
        assertThat(p.entryId()).isNull();
        assertThat(p.decide("new")).isEqualTo(Action.CREATE);
    }
}
