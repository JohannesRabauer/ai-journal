package dev.rabauer.ai.diary.views.memory;

import dev.rabauer.ai.diary.api.common.ErrorDto;
import dev.rabauer.ai.diary.api.memory.MemoryItemType;
import dev.rabauer.ai.diary.api.memory.RevealDto;
import dev.rabauer.ai.diary.api.memory.ReviewCardDto;
import dev.rabauer.ai.diary.api.memory.SourceType;
import dev.rabauer.ai.diary.client.BackendException;
import dev.rabauer.ai.diary.views.memory.MemoryPresenter.Grade;
import dev.rabauer.ai.diary.views.memory.MemoryPresenter.Phase;
import dev.rabauer.ai.diary.views.memory.MemoryPresenter.ReviewSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryPresenterTest {

    private static BackendException error(int status, String code) {
        return new BackendException(status, new ErrorDto(code, "message"));
    }

    private static ReviewCardDto card(long id) {
        return new ReviewCardDto(id, MemoryItemType.FACT, "Question " + id + "?");
    }

    private static RevealDto reveal(long id) {
        return new RevealDto(id, "Answer " + id, "quote", SourceType.ENTRY, 3L, null);
    }

    @Test
    void gradeButtonsMapToSm2Qualities() {
        assertThat(Grade.values()).extracting(Grade::quality).containsExactly(1, 3, 4, 5);
        assertThat(Grade.values()).extracting(Grade::key)
                .containsExactly("memory.grade.forgot", "memory.grade.hard", "memory.grade.good", "memory.grade.easy");
    }

    @Test
    void errorMapping() {
        assertThat(MemoryPresenter.errorFor(error(502, "LLM_OUTPUT_INVALID")))
                .isEqualTo(new MemoryPresenter.ErrorInfo("memory.error.invalid", true, false));
        assertThat(MemoryPresenter.errorFor(error(503, "PROVIDER_UNREACHABLE")).messageKey()).isEqualTo("memory.error.provider");
        assertThat(MemoryPresenter.errorFor(error(409, "ENTRY_NOT_FINISHED")))
                .isEqualTo(new MemoryPresenter.ErrorInfo("memory.error.notFinished", false, false));
        assertThat(MemoryPresenter.errorFor(error(409, "ATTEMPT_REQUIRED")).refresh()).isTrue();
        assertThat(MemoryPresenter.errorFor(error(409, "CANDIDATE_DECIDED")).refresh()).isTrue();
        assertThat(MemoryPresenter.errorFor(error(404, "NOT_FOUND")).refresh()).isTrue();
        assertThat(MemoryPresenter.errorFor(error(400, "INVALID_QUALITY")).messageKey()).isEqualTo("memory.error.badInput");
        assertThat(MemoryPresenter.errorFor(error(501, "NOT_IMPLEMENTED")).retryable()).isFalse();
        assertThat(MemoryPresenter.errorFor(error(500, "HTTP_500")).retryable()).isTrue();
        assertThat(MemoryPresenter.errorFor(new IllegalStateException("x")))
                .isEqualTo(new MemoryPresenter.ErrorInfo("memory.error.unreachable", true, false));
    }

    @Test
    void targetFromQueryParameters() {
        assertThat(MemoryPresenter.targetFrom(Map.of("source", List.of("entry"), "id", List.of("12"))))
                .contains(new MemoryPresenter.Target(SourceType.ENTRY, 12));
        assertThat(MemoryPresenter.targetFrom(Map.of("source", List.of("CONVERSATION"), "id", List.of(" 7 "))))
                .contains(new MemoryPresenter.Target(SourceType.CONVERSATION, 7));
        assertThat(MemoryPresenter.targetFrom(Map.of("source", List.of("entry")))).isEmpty();
        assertThat(MemoryPresenter.targetFrom(Map.of("source", List.of("other"), "id", List.of("1")))).isEmpty();
        assertThat(MemoryPresenter.targetFrom(Map.of("source", List.of("entry"), "id", List.of("abc")))).isEmpty();
        assertThat(MemoryPresenter.targetFrom(Map.of("source", List.of("entry"), "id", List.of("-3")))).isEmpty();
        assertThat(MemoryPresenter.targetFrom(null)).isEmpty();
        var target = new MemoryPresenter.Target(SourceType.ENTRY, 9);
        assertThat(MemoryPresenter.targetFrom(Map.of("source", List.of(target.toParameters().get("source")),
                "id", List.of(target.toParameters().get("id"))))).contains(target);
    }

    @Test
    void editValidation() {
        assertThat(MemoryPresenter.editProblem("Q?", "A")).isEmpty();
        assertThat(MemoryPresenter.editProblem("  ", "A")).contains("memory.edit.promptEmpty");
        assertThat(MemoryPresenter.editProblem(null, "A")).contains("memory.edit.promptEmpty");
        assertThat(MemoryPresenter.editProblem("Q?", " ")).contains("memory.edit.answerEmpty");
        assertThat(MemoryPresenter.editProblem("x".repeat(501), "A")).contains("memory.edit.promptTooLong");
        assertThat(MemoryPresenter.editProblem("Q?", "x".repeat(2001))).contains("memory.edit.answerTooLong");
    }

    @Test
    void attemptTextIsNullForAnswersInTheHead() {
        assertThat(MemoryPresenter.attemptText(null)).isNull();
        assertThat(MemoryPresenter.attemptText("   ")).isNull();
        assertThat(MemoryPresenter.attemptText("  my guess ")).isEqualTo("my guess");
    }

    @Test
    void sessionShowsOnlyThePromptUntilTheAnswerIsRevealed() {
        ReviewSession s = new ReviewSession(List.of(card(1), card(2)));
        assertThat(s.phase()).isEqualTo(Phase.PROMPT);
        assertThat(s.remaining()).isEqualTo(2);
        assertThat(s.current()).contains(card(1));
        assertThat(s.reveal()).isEmpty();

        s.revealed(reveal(2)); // for another card: ignored
        assertThat(s.phase()).isEqualTo(Phase.PROMPT);

        s.revealed(reveal(1));
        assertThat(s.phase()).isEqualTo(Phase.REVEALED);
        assertThat(s.reveal().orElseThrow().answer()).isEqualTo("Answer 1");

        s.graded();
        assertThat(s.phase()).isEqualTo(Phase.PROMPT);
        assertThat(s.reveal()).isEmpty(); // the next card starts hidden again
        assertThat(s.current()).contains(card(2));
        assertThat(s.remaining()).isEqualTo(1);

        s.graded();
        assertThat(s.phase()).isEqualTo(Phase.DONE);
        assertThat(s.remaining()).isZero();
        assertThat(s.current()).isEmpty();
        s.graded(); // harmless
        assertThat(s.phase()).isEqualTo(Phase.DONE);
    }

    @Test
    void emptySessionIsDone() {
        assertThat(new ReviewSession(null).phase()).isEqualTo(Phase.DONE);
        assertThat(new ReviewSession(List.of()).remaining()).isZero();
    }
}
