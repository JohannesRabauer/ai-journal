package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.coach.CoachMessageDto;
import dev.rabauer.ai.diary.api.coach.CoachMessageKind;
import dev.rabauer.ai.diary.api.coach.CoachRole;
import dev.rabauer.ai.diary.api.coach.ConversationDto;
import dev.rabauer.ai.diary.api.coach.ConversationStatus;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.web.ApiException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** Port of the Vaadin CoachPresenter tests; the answer-draft and new-question cases live in the JS tests. */
class CoachPresenterTest {

    private static final Messages EN = Messages.forAcceptable(List.of(Locale.ENGLISH));

    private static CoachMessageDto msg(long id, CoachRole role, CoachMessageKind kind) {
        return new CoachMessageDto(id, role, kind, "text " + id, false, Instant.parse("2026-01-02T10:00:00Z"));
    }

    private static ConversationDto conversation(long id, ConversationStatus status, String started, CoachMessageDto... messages) {
        return new ConversationDto(id, 7, status, Language.DE, List.of(messages), Instant.parse(started),
                Instant.parse(started), null);
    }

    private static ApiException error(Response.Status status, String code) {
        return new ApiException(status, code, "x");
    }

    @Test
    void activeOffersAnswerPauseAndEnd() {
        var a = CoachPresenter.actions(ConversationStatus.ACTIVE);
        assertThat(a.answer() && a.pause() && a.end()).isTrue();
        assertThat(a.resume() || a.startNew()).isFalse();
    }

    @Test
    void pausedOffersResumeAndEndButNoAnswer() {
        var a = CoachPresenter.actions(ConversationStatus.PAUSED);
        assertThat(a.resume() && a.end()).isTrue();
        assertThat(a.answer() || a.pause() || a.startNew()).isFalse();
    }

    @Test
    void endedIsReadOnlyWithStartNew() {
        var a = CoachPresenter.actions(ConversationStatus.ENDED);
        assertThat(a.startNew()).isTrue();
        assertThat(a.answer() || a.pause() || a.resume() || a.end()).isFalse();
    }

    @Test
    void invalidLlmOutputAndProviderErrorsAreCalmAndRetryable() {
        var invalid = CoachPresenter.errorFor(error(Response.Status.BAD_GATEWAY, "LLM_OUTPUT_INVALID"));
        assertThat(invalid.messageKey()).isEqualTo("coach.error.invalid");
        assertThat(invalid.retryable()).isTrue();
        for (String code : List.of("PROVIDER_UNAVAILABLE", "PROVIDER_TIMEOUT", "PROVIDER_ERROR")) {
            var info = CoachPresenter.errorFor(error(Response.Status.BAD_GATEWAY, code));
            assertThat(info.messageKey()).isEqualTo("coach.error.provider");
            assertThat(info.retryable()).isTrue();
        }
    }

    @Test
    void conflictMeansReloadNotRetry() {
        var info = CoachPresenter.errorFor(error(Response.Status.CONFLICT, "CONVERSATION_NOT_ACTIVE"));
        assertThat(info.reload()).isTrue();
        assertThat(info.retryable()).isFalse();
        assertThat(info.messageKey()).isEqualTo("coach.error.state");
    }

    @Test
    void notImplementedNotFoundAndUnreachable() {
        assertThat(CoachPresenter.errorFor(error(Response.Status.NOT_IMPLEMENTED, "HTTP_501")).messageKey())
                .isEqualTo("coach.error.unavailable");
        assertThat(CoachPresenter.errorFor(error(Response.Status.NOT_FOUND, "NOT_FOUND")).messageKey())
                .isEqualTo("coach.error.notFound");
        var other = CoachPresenter.errorFor(error(Response.Status.INTERNAL_SERVER_ERROR, "INTERNAL"));
        assertThat(other.messageKey()).isEqualTo("coach.error.unreachable");
        assertThat(other.retryable()).isTrue();
        assertThat(CoachPresenter.errorFor(new IllegalStateException("connection refused")).messageKey())
                .isEqualTo("coach.error.unreachable");
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

    @Test
    void bubblesAreLabelledNumberedAndMarkCurrent() {
        var c = conversation(1, ConversationStatus.ACTIVE, "2026-01-01T10:00:00Z",
                msg(1, CoachRole.COACH, CoachMessageKind.OPENING_QUESTION),
                msg(2, CoachRole.USER, CoachMessageKind.USER_ANSWER),
                msg(3, CoachRole.COACH, CoachMessageKind.FOLLOW_UP_QUESTION));
        var m = CoachPresenter.messages(c, EN, ZoneId.of("UTC"));
        assertThat(m).extracting(CoachPresenter.Message::speaker).containsExactly("Coach", "You", "Coach");
        assertThat(m).extracting(CoachPresenter.Message::number).containsExactly("Question 1", "", "Question 2");
        assertThat(m).extracting(CoachPresenter.Message::current).containsExactly(false, false, true);
    }
}
