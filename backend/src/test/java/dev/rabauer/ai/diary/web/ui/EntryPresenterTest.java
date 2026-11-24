package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Heading rule (Vaadin EntryFormatTest) and the state decisions of the entry page. */
class EntryPresenterTest {

    private static final Messages EN = Messages.forAcceptable(List.of(Locale.ENGLISH));
    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 11, 24, 7, 15);

    private static EntryDto entry(EntryStatus status, String text, EntrySource source, boolean coach, String remark) {
        return new EntryDto(5, WHEN, status, source, null, text, remark, UUID.randomUUID(), coach, null, null, null);
    }

    @Test
    void headingIsFirstSentenceOrShortenedFirstLine() {
        assertThat(EntryFormat.heading("Couldn't sleep past six. I keep replaying it.")).isEqualTo("Couldn't sleep past six");
        assertThat(EntryFormat.heading("Was it fine?\nsecond line")).isEqualTo("Was it fine?");
        assertThat(EntryFormat.heading("  ")).isEmpty();
        assertThat(EntryFormat.heading(null)).isEmpty();
        String h = EntryFormat.heading("word ".repeat(40));
        assertThat(h).endsWith("…").hasSizeLessThanOrEqualTo(62);
    }

    @Test
    void eyebrowIsFullDateAndTime() {
        assertThat(EntryFormat.detailEyebrow(entry(EntryStatus.DRAFT, "x", EntrySource.TEXT, false, null), Locale.ENGLISH))
                .startsWith("Tuesday, November 24, 2026").contains("·").contains("7:15");
        assertThat(EntryFormat.detailEyebrow(new EntryDto(1, null, EntryStatus.DRAFT, EntrySource.TEXT, null, "x", null,
                null, false, null, null, null), Locale.ENGLISH)).isEmpty();
    }

    @Test
    void finishedTextEntryOffersEditExtractAndDeleteAndReflect() {
        var v = EntryPresenter.view(entry(EntryStatus.FINISHED, "Done. More.", EntrySource.TEXT, false, null), null, false, false, null, EN);
        assertThat(v.heading()).isEqualTo("Done");
        assertThat(v.statusLabel()).isNull();
        assertThat(v.panel()).isEqualTo(EntryPresenter.Panel.EDIT);
        assertThat(v.showFinish()).isFalse();
        assertThat(v.showEdit()).isTrue();
        assertThat(v.showExtract()).isTrue();
        assertThat(v.extractHref()).isEqualTo("/memory?source=entry&id=5");
        assertThat(v.showDeleteEntry()).isTrue();
        assertThat(v.showDeleteAudio()).isFalse();
    }

    @Test
    void coachConversationShowsTheReflectionChip() {
        var v = EntryPresenter.view(entry(EntryStatus.FINISHED, "x", EntrySource.TEXT, true, null), null, false, false, null, EN);
        assertThat(v.coach()).isTrue();
    }

    @Test
    void draftEntryOffersFinishNotExtract() {
        var v = EntryPresenter.view(entry(EntryStatus.DRAFT, "x", EntrySource.TEXT, false, null), null, false, false, null, EN);
        assertThat(v.statusLabel()).isEqualTo("Draft");
        assertThat(v.showFinish()).isTrue();
        assertThat(v.showExtract()).isFalse();
    }

    @Test
    void blankTextFallsBackToTheEntryHeading() {
        var v = EntryPresenter.view(entry(EntryStatus.DRAFT, " ", EntrySource.TEXT, false, null), null, false, false, null, EN);
        assertThat(v.heading()).isEqualTo("Entry");
        assertThat(v.finishDisabled()).isTrue();
    }

    @Test
    void transcribingShowsProgressAndOffersNoDelete() {
        var v = EntryPresenter.view(entry(EntryStatus.TRANSCRIBING, "", EntrySource.VOICE, false, null), UUID.randomUUID().toString(), false, false, null, EN);
        assertThat(v.panel()).isEqualTo(EntryPresenter.Panel.TRANSCRIBING);
        assertThat(v.voice()).isTrue();
        assertThat(v.showDeleteEntry()).isFalse();
        assertThat(v.showDeleteAudio()).isFalse();
        assertThat(v.showEdit()).isFalse();
    }

    @Test
    void failedTranscriptionOffersWritingItYourselfWithoutScripts() {
        var failed = EntryPresenter.view(entry(EntryStatus.TRANSCRIPTION_FAILED, "", EntrySource.VOICE, false, null), "r", false, false, null, EN);
        assertThat(failed.panel()).isEqualTo(EntryPresenter.Panel.FAILED);
        assertThat(failed.typeManuallyHref()).isEqualTo("/journal/5?manual=1#entry-text");
        assertThat(failed.showDeleteAudio()).isTrue();

        var manual = EntryPresenter.view(entry(EntryStatus.TRANSCRIPTION_FAILED, "", EntrySource.VOICE, false, null), "r", false, true, null, EN);
        assertThat(manual.panel()).isEqualTo(EntryPresenter.Panel.EDIT);
        assertThat(manual.typeManuallyHref()).isNull();
    }

    @Test
    void transcriptUnderReviewHasHelperAndReviewLabel() {
        var v = EntryPresenter.view(entry(EntryStatus.TRANSCRIBED, "Hello", EntrySource.VOICE, false, null), "r", false, false, null, EN);
        assertThat(v.panelLabel()).isEqualTo("Transcript");
        assertThat(v.panelHelper()).isNotBlank();
        assertThat(v.showFinish()).isTrue();
        assertThat(v.statusLabel()).isEqualTo("Ready to review");
        assertThat(v.hasAudio()).isTrue();
        assertThat(v.showDeleteAudio()).isTrue();
    }

    @Test
    void legacyRemarkAndNoticeAreCarried() {
        var v = EntryPresenter.view(entry(EntryStatus.FINISHED, "x", EntrySource.TEXT, false, "old remark"), null, true, false,
                EntryPresenter.Notice.of("audioDeleted"), EN);
        assertThat(v.legacyRemark()).isEqualTo("old remark");
        assertThat(v.audioDeleted()).isTrue();
        assertThat(v.notice()).isEqualTo(EN.get("entries.audio.deleted"));
        assertThat(EntryPresenter.Notice.of("nonsense")).isNull();
        assertThat(EntryPresenter.Notice.of(null)).isNull();
    }
}
