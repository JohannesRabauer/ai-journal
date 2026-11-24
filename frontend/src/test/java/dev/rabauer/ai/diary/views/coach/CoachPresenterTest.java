package dev.rabauer.ai.diary.views.coach;

import dev.rabauer.ai.diary.api.coach.CoachMessageDto;
import dev.rabauer.ai.diary.api.coach.CoachMessageKind;
import dev.rabauer.ai.diary.api.coach.CoachRole;
import dev.rabauer.ai.diary.api.coach.ConversationDto;
import dev.rabauer.ai.diary.api.coach.ConversationStatus;
import dev.rabauer.ai.diary.api.common.ErrorDto;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.client.BackendException;
import dev.rabauer.ai.diary.views.coach.CoachPresenter.AnswerDraft;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CoachPresenterTest {

    private static CoachMessageDto msg(long id, CoachRole role, CoachMessageKind kind) {
        return new CoachMessageDto(id, role, kind, "text " + id, false, Instant.parse("2026-01-02T10:00:00Z"));
    }

    private static ConversationDto conversation(long id, ConversationStatus status, String started,
                                                CoachMessageDto... messages) {
        return new ConversationDto(id, 7, status, Language.DE, List.of(messages), Instant.parse(started),
                Instant.parse(started), null);
    }

    @Test
    void activeOffersAnswerPauseAndEnd() {
        var a = CoachPresenter.actions(ConversationStatus.ACTIVE);
        assertThat(a.answer()).isTrue();
        assertThat(a.pause()).isTrue();
        assertThat(a.end()).isTrue();
        assertThat(a.resume()).isFalse();
        assertThat(a.startNew()).isFalse();
    }

    @Test
    void pausedOffersResumeAndEndButNoAnswer() {
        var a = CoachPresenter.actions(ConversationStatus.PAUSED);
        assertThat(a.resume()).isTrue();
        assertThat(a.end()).isTrue();
        assertThat(a.answer()).isFalse();
        assertThat(a.pause()).isFalse();
        assertThat(a.startNew()).isFalse();
    }

    @Test
    void endedIsReadOnlyWithStartNew() {
        var a = CoachPresenter.actions(ConversationStatus.ENDED);
        assertThat(a.startNew()).isTrue();
        assertThat(a.answer() || a.pause() || a.resume() || a.end()).isFalse();
    }

    @Test
    void invalidLlmOutputAndProviderErrorsAreCalmAndRetryable() {
        var invalid = CoachPresenter.errorFor(new BackendException(502, new ErrorDto("LLM_OUTPUT_INVALID", "x")));
        assertThat(invalid.messageKey()).isEqualTo("coach.error.invalid");
        assertThat(invalid.retryable()).isTrue();
        for (String code : List.of("PROVIDER_UNAVAILABLE", "PROVIDER_TIMEOUT", "PROVIDER_ERROR")) {
            var info = CoachPresenter.errorFor(new BackendException(502, new ErrorDto(code, "x")));
            assertThat(info.messageKey()).isEqualTo("coach.error.provider");
            assertThat(info.retryable()).isTrue();
        }
    }

    @Test
    void conflictMeansReloadNotRetry() {
        var info = CoachPresenter.errorFor(new BackendException(409, new ErrorDto("CONVERSATION_NOT_ACTIVE", "x")));
        assertThat(info.reload()).isTrue();
        assertThat(info.retryable()).isFalse();
        assertThat(info.messageKey()).isEqualTo("coach.error.state");
    }

    @Test
    void notImplementedNotFoundAndUnreachable() {
        assertThat(CoachPresenter.errorFor(new BackendException(501, new ErrorDto("HTTP_501", "x"))).messageKey())
                .isEqualTo("coach.error.unavailable");
        assertThat(CoachPresenter.errorFor(new BackendException(404, new ErrorDto("NOT_FOUND", "x"))).messageKey())
                .isEqualTo("coach.error.notFound");
        var other = CoachPresenter.errorFor(new BackendException(500, new ErrorDto("INTERNAL", "x")));
        assertThat(other.messageKey()).isEqualTo("coach.error.unreachable");
        assertThat(other.retryable()).isTrue();
        assertThat(CoachPresenter.errorFor(new IllegalStateException("connection refused")).messageKey())
                .isEqualTo("coach.error.unreachable");
    }

    @Test
    void errorMessagesNeverContainBackendText() {
        var info = CoachPresenter.errorFor(new BackendException(502, new ErrorDto("LLM_OUTPUT_INVALID", "my secret diary text")));
        assertThat(info.messageKey()).doesNotContain("secret");
    }

    @Test
    void latestPicksMostRecentlyStarted() {
        var old = conversation(9, ConversationStatus.ENDED, "2026-01-01T10:00:00Z");
        var recent = conversation(3, ConversationStatus.PAUSED, "2026-02-01T10:00:00Z");
        assertThat(CoachPresenter.latest(List.of(old, recent))).contains(recent);
        assertThat(CoachPresenter.latest(List.of())).isEmpty();
        assertThat(CoachPresenter.latest(null)).isEmpty();
    }

    @Test
    void currentQuestionIsTheTrailingCoachQuestionOfAnActiveConversation() {
        var asked = conversation(1, ConversationStatus.ACTIVE, "2026-01-01T10:00:00Z",
                msg(1, CoachRole.COACH, CoachMessageKind.OPENING_QUESTION));
        assertThat(CoachPresenter.currentQuestion(asked)).isPresent();

        var answered = conversation(1, ConversationStatus.ACTIVE, "2026-01-01T10:00:00Z",
                msg(1, CoachRole.COACH, CoachMessageKind.OPENING_QUESTION), msg(2, CoachRole.USER, CoachMessageKind.USER_ANSWER));
        assertThat(CoachPresenter.currentQuestion(answered)).isEmpty();

        var explained = conversation(1, ConversationStatus.ACTIVE, "2026-01-01T10:00:00Z",
                msg(3, CoachRole.COACH, CoachMessageKind.EXPLANATION));
        assertThat(CoachPresenter.currentQuestion(explained)).isEmpty();

        var paused = conversation(1, ConversationStatus.PAUSED, "2026-01-01T10:00:00Z",
                msg(1, CoachRole.COACH, CoachMessageKind.FOLLOW_UP_QUESTION));
        assertThat(CoachPresenter.currentQuestion(paused)).isEmpty();
    }

    @Test
    void onlyNewCoachQuestionsAreSpoken() {
        var c = conversation(1, ConversationStatus.ACTIVE, "2026-01-01T10:00:00Z",
                msg(1, CoachRole.COACH, CoachMessageKind.OPENING_QUESTION),
                msg(2, CoachRole.USER, CoachMessageKind.USER_ANSWER),
                msg(3, CoachRole.COACH, CoachMessageKind.EXPLANATION),
                msg(4, CoachRole.COACH, CoachMessageKind.FOLLOW_UP_QUESTION));
        assertThat(CoachPresenter.newQuestions(Set.of(), c)).extracting(CoachMessageDto::id).containsExactly(1L, 4L);
        assertThat(CoachPresenter.newQuestions(Set.of(1L, 2L, 3L), c)).extracting(CoachMessageDto::id).containsExactly(4L);
        assertThat(CoachPresenter.newQuestions(Set.of(1L, 4L), c)).isEmpty();
    }

    @Test
    void speechLanguageAndVoiceSelection() {
        assertThat(CoachPresenter.speechLanguage(Language.DE, Locale.ENGLISH)).isEqualTo(Language.DE);
        assertThat(CoachPresenter.speechLanguage(Language.AUTO, Locale.GERMAN)).isEqualTo(Language.DE);
        assertThat(CoachPresenter.speechLanguage(null, Locale.FRENCH)).isEqualTo(Language.EN);
        assertThat(CoachPresenter.voiceFor(Language.DE, "de_a", "en_b")).isEqualTo("de_a");
        assertThat(CoachPresenter.voiceFor(Language.EN, "de_a", " ")).isNull();
    }

    @Test
    void timeIsFormattedInTheGivenZone() {
        String s = CoachPresenter.time(Instant.parse("2026-01-02T23:30:00Z"), ZoneId.of("Europe/Berlin"), Locale.GERMANY);
        assertThat(s).contains("03.01.2026").contains("00:30");
        assertThat(CoachPresenter.time(null, ZoneId.of("UTC"), Locale.ENGLISH)).isEmpty();
    }

    // ---- answer draft

    @Test
    void typedAnswerIsNotVoice() {
        var d = new AnswerDraft();
        d.edited("Ich war müde.");
        assertThat(d.request().voice()).isFalse();
        assertThat(d.request().text()).isEqualTo("Ich war müde.");
    }

    @Test
    void transcribedAnswerIsVoiceEvenAfterEditing() {
        var d = new AnswerDraft();
        assertThat(d.transcribed("  ich war müde ")).isEqualTo("ich war müde");
        d.edited("Ich war sehr müde.");
        var r = d.request();
        assertThat(r.voice()).isTrue();
        assertThat(r.text()).isEqualTo("Ich war sehr müde.");
    }

    @Test
    void transcriptionIsAddedToExistingText() {
        var d = new AnswerDraft();
        d.edited("Erstens das.");
        assertThat(d.transcribed("Zweitens das.")).isEqualTo("Erstens das. Zweitens das.");
        assertThat(d.voice()).isTrue();
    }

    @Test
    void emptyingTheFieldResetsTheVoiceFlag() {
        var d = new AnswerDraft();
        d.transcribed("hallo");
        d.edited("");
        d.edited("neu getippt");
        assertThat(d.request().voice()).isFalse();
    }

    @Test
    void blankTranscriptChangesNothing() {
        var d = new AnswerDraft();
        assertThat(d.transcribed("   ")).isEmpty();
        assertThat(d.voice()).isFalse();
        assertThat(d.request()).isNull();
    }

    @Test
    void failedSendKeepsTextAndVoiceFlag() {
        var d = new AnswerDraft();
        d.transcribed("das ist wichtig");
        assertThat(d.request()).isNotNull();
        d.sendFailed();
        assertThat(d.text()).isEqualTo("das ist wichtig");
        assertThat(d.voice()).isTrue();
        assertThat(d.request()).isNotNull();
    }

    @Test
    void successfulSendClearsEverything() {
        var d = new AnswerDraft();
        d.transcribed("das ist wichtig");
        d.sent();
        assertThat(d.text()).isEmpty();
        assertThat(d.voice()).isFalse();
        assertThat(d.request()).isNull();
    }

    @Test
    void blankAnswerIsNeverSent() {
        var d = new AnswerDraft();
        d.edited("   \n ");
        assertThat(d.request()).isNull();
    }
}
