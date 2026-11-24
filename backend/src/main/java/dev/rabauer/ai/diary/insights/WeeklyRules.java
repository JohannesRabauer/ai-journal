package dev.rabauer.ai.diary.insights;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Semantic checks for generated weekly reflections (SPEC sections 5 and 8): references must exist, quotes must be the
 * user's own words, no scores, no health claims, no generic praise, no advice, one question.
 *
 * <p>Problem texts never contain journal content (they are logged by the structured-output service and fed back to
 * the model). The checks apply to the generated text only; quotes are the user's own words and only have to be
 * verbatim; user edits are not checked at all.
 *
 * <p>The word lists are a pragmatic trade-off: stems that are almost never needed in a neutral description are
 * rejected outright (depress, burnout, diagnos, ...); words with legitimate uses (therapy, healthy, krank) only in
 * claim form (addressed to the user, or "you should/need ..."). Some false positives are accepted: the model is
 * asked to rephrase, and after three rejections nothing is stored.
 */
final class WeeklyRules {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    static final int MIN_QUOTE_LENGTH = 8;

    /**
     * Score-like TERMS: x/10, "out of 10", score(s), rating(s), numbers with points/stars/Punkte, percentages, "Punkte".
     * A term may be used by the model only if the same term occurs in the week's input (the user wrote it).
     */
    static final Pattern SCORE_TERM = Pattern.compile(
            "\\d+(?:[.,]\\d+)?\\s*/\\s*(?:10|100|5|7)\\b"
                    + "|\\b(?:out of|von)\\s+(?:10|100|5|7)\\b"
                    + "|\\b(?:score[sd]?|scoring|ratings?|punktzahl|bewertungsskala)\\b"
                    + "|\\b\\d+(?:[.,]\\d+)?\\s*(?:points?|punkte[n]?|stars?|sterne[n]?)\\b"
                    + "|\\bpunkte[n]?\\b"
                    + "|\\d+(?:[.,]\\d+)?\\s*%|%",
            FLAGS);

    /** Mood or rating aimed at the user, e.g. "your mood: 4/10", "Stimmung bei 8": rejected even if the input has the words. */
    static final Pattern SCORE_CLAIM = Pattern.compile(
            "\\b(?:mood|stimmung|laune)\\w*\\s*(?:[:=]|of|von|bei)?\\s*\\d"
                    + "|\\b(?:your|dein\\w*)\\s+(?:mood|stimmung|laune|energy|energie)\\b.{0,20}\\d",
            FLAGS);

    /** Health TERMS: stems never needed for a neutral description. Allowed only if the user used the same word. */
    static final Pattern HEALTH_TERM = Pattern.compile(
            "\\bdepress(?!ing)\\w*|\\bdeprimier\\w*|\\bburn-?out\\b|\\bausgebrannt\\b|\\bdiagnos\\w*"
                    + "|\\bmental (?:illness|disorder|health)\\b|\\bpsychische\\w* (?:erkrank|st\u00f6r)\\w*"
                    + "|\\bangstst\u00f6rung\\w*|\\banxiety disorder\\b|\\bpanic attack\\w*|\\bpanikattacke\\w*"
                    + "|\\btraumatis\\w*|\\bposttraumatic\\b",
            FLAGS);

    /**
     * Health CLAIMS, rejected even if the words occur in the input: therapy/treatment advice ("you should start
     * therapy"), "you are/du bist/wirkst ... healthy/sick/krank", "your health", and a condition attributed to the user
     * ("du hast ein Burnout", "you are depressed", "you suffer from anxiety").
     */
    static final Pattern HEALTH_CLAIM = Pattern.compile(
            "(?:recommend|empfehl|brauch|need|should|sollt|solltest)\\w*\\b.{0,40}\\b(?:therap|medikat|medicat|behandl|treatment)\\w*"
                    + "|\\b(?:you|du)\\b.{0,25}\\b(?:are|bist|seem|wirkst|scheinst|look|sound|klingst)\\b.{0,15}\\b(?:healthy|gesund|ungesund|unhealthy|sick|ill|krank)\\b"
                    + "|\\b(?:your|dein|deine[nmrs]?)\\s+(?:mental\\s+|psychische\\w*\\s+)?(?:health|gesundheit)\\b"
                    + "|\\b(?:you|du|dir|dich)\\b.{0,30}\\b(?:depress\\w*|deprimier\\w*|burn-?out|ausgebrannt|diagnos\\w*|angstst\u00f6r\\w*|anxiety disorder|panic|panik\\w*|trauma\\w*)",
            FLAGS);

    static final Pattern PRAISE = Pattern.compile(
            "\\bgreat (?:job|work|insight|progress|effort)\\b|\\bwell done\\b|\\bgood job\\b|\\bnicely done\\b|\\bwonderful\\b"
                    + "|\\bamazing\\b|\\bawesome\\b|\\bfantastic\\b|\\bexcellent\\b|\\bbrilliant\\b|\\bimpressive\\b"
                    + "|\\bproud of you\\b|\\bkeep it up\\b|\\bkeep up the good\\b|\\bcongrat\\w*|\\bgood for you\\b|\\byou did (?:great|well)\\b"
                    + "|\\bgut gemacht\\b|\\bsuper gemacht\\b|\\btoll(?:e[rsnm]?)?\\b|\\bgro(?:ß|ss)artig\\w*|\\bwunderbar\\w*"
                    + "|\\bfantastisch\\w*|\\bausgezeichnet\\w*|\\bhervorragend\\w*|\\bbravo\\b|\\bstolz auf dich\\b|\\bweiter so\\b"
                    + "|\\bgl(?:ü|ue)ckwunsch\\w*|\\bbeeindruckend\\w*|\\bprima\\b",
            FLAGS);

    /** Advice phrasing; only applied to descriptive fields (theme descriptions, patterns). */
    static final Pattern ADVICE = Pattern.compile(
            "\\byou (?:should|must|need to|ought to|could try|might want to)\\b|\\btry to\\b|\\bi recommend\\b"
                    + "|\\bit would be (?:good|better|helpful|wise) to\\b|\\bconsider \\w+ing\\b"
                    + "|\\bdu (?:solltest|musst|könntest|müsstest)\\b|\\bversuche\\b|\\bich empfehle\\b|\\bes wäre (?:gut|besser|hilfreich|klug)\\b",
            FLAGS);

    private WeeklyRules() {
    }

    /** @param entryTexts the week's input: entry id to its full text */
    static List<String> check(WeeklyLlmOutput out, Map<Long, String> entryTexts) {
        return check(out, entryTexts, "");
    }

    /**
     * @param entryTexts the week's input: entry id to its full text
     * @param moreInput  further input text of the week (coach lines); together with the entries it decides which score
     *                   and health terms the user used themselves and the model may therefore repeat
     */
    static List<String> check(WeeklyLlmOutput out, Map<Long, String> entryTexts, String moreInput) {
        String input = flat(String.join(" ", entryTexts.values()) + " " + moreInput);
        List<String> problems = new ArrayList<>();
        Set<Long> ids = entryTexts.keySet();

        for (int i = 0; i < out.recurringThemes().size(); i++) {
            WeeklyLlmOutput.Theme t = out.recurringThemes().get(i);
            for (Long id : t.entryIds()) {
                if (!ids.contains(id)) {
                    problems.add("theme " + (i + 1) + " references entry id " + id + " which is not part of this week's entries");
                }
            }
            textProblems("theme " + (i + 1) + " title", t.title(), false, input, problems);
            textProblems("theme " + (i + 1) + " description", t.description(), true, input, problems);
        }

        for (int i = 0; i < out.inYourOwnWords().size(); i++) {
            WeeklyLlmOutput.Quote q = out.inYourOwnWords().get(i);
            String label = "quote " + (i + 1);
            String source = ids.contains(q.entryId()) ? entryTexts.get(q.entryId()) : null;
            if (source == null) {
                problems.add(label + " references entry id " + q.entryId() + " which is not part of this week's entries");
                continue;
            }
            String text = cleanQuote(q.text());
            if (text.length() < MIN_QUOTE_LENGTH) {
                problems.add(label + " is too short; quote a meaningful phrase");
            } else if (!normalize(source).contains(normalize(text))) {
                problems.add(label + " is not a verbatim excerpt of entry " + q.entryId()
                        + "; copy the words exactly or choose another passage");
            }
        }

        for (int i = 0; i < out.openThreads().size(); i++) {
            textProblems("open thread " + (i + 1), out.openThreads().get(i), false, input, problems);
        }
        for (int i = 0; i < out.noticedPatterns().size(); i++) {
            textProblems("noticed pattern " + (i + 1), out.noticedPatterns().get(i), true, input, problems);
        }

        String question = out.questionForNextWeek().strip();
        if (!question.endsWith("?")) {
            problems.add("questionForNextWeek must end with a question mark");
        } else if (question.chars().filter(c -> c == '?').count() != 1) {
            problems.add("questionForNextWeek must be exactly one question");
        }
        textProblems("questionForNextWeek", question, false, input, problems);
        return problems;
    }

    private static void textProblems(String label, String text, boolean checkAdvice, String input, List<String> problems) {
        if (SCORE_CLAIM.matcher(text).find() || unusedTerm(SCORE_TERM, text, input, false)) {
            problems.add(label + " contains a score, rating, percentage or number-of-points that the entries do not use; describe without numbers of that kind");
        }
        if (HEALTH_CLAIM.matcher(text).find() || unusedTerm(HEALTH_TERM, text, input, true)) {
            problems.add(label + " contains a health, medical or therapy statement; describe only what the entries say");
        }
        if (PRAISE.matcher(text).find()) {
            problems.add(label + " contains praise; describe neutrally without praise or flattery");
        }
        if (checkAdvice && ADVICE.matcher(text).find()) {
            problems.add(label + " contains advice; describe only, give no advice");
        }
    }

    /**
     * True if {@code text} has a match of {@code terms} that does not occur in the (flattened) input. For stems
     * (health words) the first six letters count, so "depressed" in the input allows "depression".
     */
    private static boolean unusedTerm(Pattern terms, String text, String input, boolean stem) {
        var m = terms.matcher(text);
        while (m.find()) {
            String term = flat(m.group());
            if (stem && term.length() > 6) {
                term = term.substring(0, 6);
            }
            if (!input.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /** Normalized like quotes, with all whitespace and hyphens removed ("50 %" equals "50%", "Burn-out" equals "burnout"). */
    static String flat(String s) {
        return normalize(s).replaceAll("[\\s-]+", "");
    }

    /** Whitespace collapsed, case folded, typographic quotes unified. */
    static String normalize(String s) {
        return s.replace('’', '\'').replace('‘', '\'')
                .replace('“', '"').replace('”', '"').replace('„', '"')
                .replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    /** Removes wrapping quotation marks and the prompt's escaping of angle brackets, so the text can be compared and stored. */
    static String cleanQuote(String text) {
        String t = text.strip().replace("&lt;", "<").replace("&gt;", ">");
        String quotes = "\"'“”„‘’«»";
        int start = 0;
        int end = t.length();
        while (start < end && quotes.indexOf(t.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && quotes.indexOf(t.charAt(end - 1)) >= 0) {
            end--;
        }
        return t.substring(start, end).strip();
    }
}
