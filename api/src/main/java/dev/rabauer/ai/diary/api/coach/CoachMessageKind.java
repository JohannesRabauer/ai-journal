package dev.rabauer.ai.diary.api.coach;

/** EXPLANATION only ever follows an explicit user request (SPEC section 5, rule 3). */
public enum CoachMessageKind {
    OPENING_QUESTION, FOLLOW_UP_QUESTION, EXPLANATION, CLOSING, USER_ANSWER
}
