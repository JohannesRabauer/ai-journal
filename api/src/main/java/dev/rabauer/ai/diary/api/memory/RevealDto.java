package dev.rabauer.ai.diary.api.memory;

/**
 * Only returned after an attempt was recorded for the current review.
 *
 * @param nextIntervals days until the next review for each grade button, projected with the configured scheduler
 *                      without changing anything (null from servers that do not send it)
 */
public record RevealDto(long itemId, String answer, String sourceQuote, SourceType sourceType, Long sourceId,
                        String attemptText, NextIntervals nextIntervals) {

    /** Projected interval in days per grade: forgot (quality 1), hard (3), good (4), easy (5). */
    public record NextIntervals(int forgot, int hard, int good, int easy) {
    }

    /** Without projection (kept for callers that predate the field). */
    public RevealDto(long itemId, String answer, String sourceQuote, SourceType sourceType, Long sourceId,
                     String attemptText) {
        this(itemId, answer, sourceQuote, sourceType, sourceId, attemptText, null);
    }
}
