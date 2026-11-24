package dev.rabauer.ai.diary.web.ui;

/**
 * A one-line status shown after a no-JavaScript form post (the route redirects with a short code in the query string,
 * never with user text) or after an in-place update. {@link #NONE} renders an empty live region.
 */
public record Flash(String text, boolean error) {

    public static final Flash NONE = new Flash("", false);
}
