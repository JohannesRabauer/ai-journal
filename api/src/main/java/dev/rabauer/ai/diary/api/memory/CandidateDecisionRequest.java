package dev.rabauer.ai.diary.api.memory;

/** APPROVE uses the (optionally edited) prompt/answer/type; null keeps the candidate's value. */
public record CandidateDecisionRequest(Decision decision, String prompt, String answer, MemoryItemType type) {
    public enum Decision { APPROVE, DISCARD }
}
