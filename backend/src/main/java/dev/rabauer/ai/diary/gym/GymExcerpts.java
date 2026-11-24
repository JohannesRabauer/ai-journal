package dev.rabauer.ai.diary.gym;

import java.util.Optional;

/**
 * Verifies that an excerpt quoted by the LLM really occurs in the journal entry. Comparison ignores case, runs of
 * whitespace, and typographic variants of quotes and dashes; the text returned is the entry's own wording.
 */
final class GymExcerpts {

    static final int MIN_CHARS = 10;
    static final int MAX_CHARS = 400;

    private GymExcerpts() {
    }

    /** @return the matching passage exactly as written in the entry, or empty if it is not a verbatim passage */
    static Optional<String> findVerbatim(String entryText, String excerpt) {
        if (entryText == null || excerpt == null) {
            return Optional.empty();
        }
        Normalized entry = normalize(entryText);
        String needle = normalize(excerpt).text.strip();
        if (needle.length() < MIN_CHARS) {
            return Optional.empty();
        }
        int start = entry.text.indexOf(needle);
        if (start < 0) {
            return Optional.empty();
        }
        int end = start + needle.length() - 1;
        String original = entryText.substring(entry.origin[start], entry.origin[end] + 1);
        return original.length() > MAX_CHARS ? Optional.empty() : Optional.of(original);
    }

    private record Normalized(String text, int[] origin) {
    }

    private static Normalized normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int[] origin = new int[s.length()];
        boolean lastSpace = true; // drops leading whitespace
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!lastSpace) {
                    origin[sb.length()] = i;
                    sb.append(' ');
                    lastSpace = true;
                }
                continue;
            }
            lastSpace = false;
            origin[sb.length()] = i;
            sb.append(fold(c));
        }
        return new Normalized(sb.toString(), origin);
    }

    private static char fold(char c) {
        return switch (c) {
            case '’', '‘', '´', '`' -> '\'';
            case '“', '”', '„', '«', '»' -> '"';
            case '–', '—' -> '-';
            default -> Character.toLowerCase(c);
        };
    }
}
