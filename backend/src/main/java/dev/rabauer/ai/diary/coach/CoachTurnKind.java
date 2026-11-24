package dev.rabauer.ai.diary.coach;

import dev.rabauer.ai.diary.api.coach.CoachMessageKind;

/** The kinds the LLM may choose for a coach turn (opening questions are a separate request). */
public enum CoachTurnKind {
    FOLLOW_UP_QUESTION, EXPLANATION, CLOSING;

    CoachMessageKind messageKind() {
        return CoachMessageKind.valueOf(name());
    }
}
