package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.entry.EntryDto;

import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/** Pure formatting of one entry for the detail page (port of the Vaadin EntryFormat and EntryListPresenter parts). */
public final class EntryFormat {

    private EntryFormat() {
    }

    /**
     * Heading for an entry detail page: the first sentence of the text (at most 80 characters), otherwise the first
     * words with an ellipsis. Empty when the text is blank (the caller shows its "Entry" fallback).
     */
    public static String heading(String text) {
        String first = JournalPresenter.preview(text, Integer.MAX_VALUE);
        if (first.isEmpty()) {
            return "";
        }
        for (int i = 0; i < first.length() && i < 80; i++) {
            char c = first.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && (i + 1 == first.length() || Character.isWhitespace(first.charAt(i + 1)))) {
                String sentence = first.substring(0, c == '.' ? i : i + 1).strip();
                if (!sentence.isEmpty()) {
                    return sentence;
                }
            }
        }
        if (first.length() <= 60) {
            return first;
        }
        int cut = first.lastIndexOf(' ', 60);
        return first.substring(0, cut > 20 ? cut : 60).stripTrailing() + "…";
    }

    /** Eyebrow of the detail page: full date and time, e.g. "Tuesday, November 24, 2026 · 7:15 AM". */
    public static String detailEyebrow(EntryDto e, Locale locale) {
        if (e.entryTimestamp() == null) {
            return "";
        }
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(e.entryTimestamp())
                + " · "
                + DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(e.entryTimestamp());
    }
}
