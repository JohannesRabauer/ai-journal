package dev.rabauer.ai.diary.api.privacy;

/** {@code confirmation} must equal {@link #CONFIRMATION_PHRASE} exactly; otherwise 400. */
public record DeleteAllRequest(String confirmation) {
    public static final String CONFIRMATION_PHRASE = "DELETE EVERYTHING";
}
