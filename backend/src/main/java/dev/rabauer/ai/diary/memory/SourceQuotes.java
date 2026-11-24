package dev.rabauer.ai.diary.memory;

import java.util.Locale;

/** Verbatim-quote check: whitespace, case and typographic quotes/dashes are normalized, nothing else. */
final class SourceQuotes {

    private SourceQuotes() {
    }

    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('’', '\'').replace('‘', '\'')
                .replace('“', '"').replace('”', '"').replace('„', '"')
                .replace('–', '-').replace('—', '-');
        return t.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static boolean isVerbatim(String quote, String normalizedSource) {
        String q = normalize(quote);
        return !q.isEmpty() && normalizedSource.contains(q);
    }
}
