package dev.rabauer.ai.diary.tts;

import dev.rabauer.ai.diary.api.common.Language;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Tiny de/en heuristic for {@code Language.AUTO}: umlauts and common German function words, otherwise English. */
final class LanguageGuess {

    private static final Set<String> DE = Set.of("ich", "und", "der", "die", "das", "nicht", "ist", "war", "wie", "was", "du",
            "mit", "ein", "eine", "heute", "dein", "deine", "habe", "hast", "wir", "auf", "für", "auch", "warum", "wann",
            "woran", "worüber", "es", "zu", "den", "dem", "sich", "wenn", "aber", "noch", "mir", "mich", "dir");
    private static final Set<String> EN = Set.of("i", "and", "the", "not", "is", "was", "how", "what", "you", "with", "a",
            "an", "today", "your", "have", "we", "on", "for", "also", "why", "when", "it", "to", "of", "that", "this",
            "but", "me", "my", "are", "do", "did");
    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}]+");

    private LanguageGuess() {
    }

    static Language of(String text) {
        int de = 0;
        int en = 0;
        for (String w : SPLIT.split(text.toLowerCase(Locale.ROOT))) {
            if (DE.contains(w)) {
                de++;
            }
            if (EN.contains(w)) {
                en++;
            }
        }
        for (char c : text.toCharArray()) {
            if ("äöüßÄÖÜ".indexOf(c) >= 0) {
                de += 2;
            }
        }
        return de > en ? Language.DE : Language.EN;
    }
}
