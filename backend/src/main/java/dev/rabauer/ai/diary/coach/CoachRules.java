package dev.rabauer.ai.diary.coach;

import dev.rabauer.ai.diary.api.common.Language;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic coach rules (SPEC section 5), decided in Java and never by the LLM: language detection, crisis
 * keywords, "explicit request for an explanation", "user is done", and the semantic checks for generated turns.
 *
 * <p>All matching is done on lower-cased text with typographic apostrophes normalised. None of these methods
 * logs or returns the text it inspects.
 *
 * <h2>Praise list (rule 4)</h2>
 * Phrases are directed at the user ("great insight", "well done", "das ist toll", "gut gemacht", "ich bin stolz auf
 * dich") or are sentence-initial interjections followed by punctuation ("Super!", "Great,"). Single evaluative
 * words in the middle of a sentence ("wonderful", "großartig", "sehr gut") are NOT matched on their own, because the
 * coach legitimately quotes the user ("What made the day wonderful?"). Retries are cheap, false negatives are
 * acceptable, false positives on quoted words are not.
 */
public final class CoachRules {

    /** At most ~60 words per coach turn (SPEC 5.6). */
    public static final int MAX_WORDS = 60;

    private CoachRules() {
    }

    // ------------------------------------------------------------------ language

    private static final Set<String> DE_WORDS = Set.of("der", "die", "das", "und", "ich", "nicht", "ist", "ein", "eine", "mit",
            "mir", "mich", "zu", "den", "dem", "auch", "es", "war", "habe", "hab", "aber", "wie", "was", "dass", "für", "von",
            "auf", "im", "noch", "so", "wenn", "schon", "sehr", "mein", "meine", "heute", "dir", "du", "wir", "sind", "bin",
            "oder", "nur", "mal", "weil", "wurde", "dann", "kann", "einfach");
    private static final Set<String> EN_WORDS = Set.of("the", "and", "i", "is", "was", "to", "of", "a", "in", "that", "it",
            "my", "me", "with", "for", "but", "not", "have", "had", "this", "on", "at", "so", "just", "about", "you", "we",
            "are", "am", "be", "been", "or", "because", "then", "can", "today", "when", "what", "how", "would", "really");

    /** Simple stopword/umlaut heuristic; ties and empty text default to English. Never calls an LLM. */
    public static Language detectLanguage(String text) {
        if (text == null || text.isBlank()) {
            return Language.EN;
        }
        int de = 0;
        int en = 0;
        for (String token : normalize(text).split("[^\\p{L}']+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (DE_WORDS.contains(token)) {
                de++;
            }
            if (EN_WORDS.contains(token)) {
                en++;
            }
            if (token.indexOf('ä') >= 0 || token.indexOf('ö') >= 0 || token.indexOf('ü') >= 0 || token.indexOf('ß') >= 0) {
                de++;
            }
        }
        return de > en ? Language.DE : Language.EN;
    }

    // ------------------------------------------------------------------ crisis (SPEC 5.7)

    /** Conservative list of unambiguous phrases about suicide and self-harm. */
    static final List<String> CRISIS_PHRASES = List.of(
            // en
            "suicide", "suicidal", "kill myself", "killing myself", "end my life", "take my own life", "want to die",
            "wanna die", "hurt myself", "harm myself", "self-harm", "self harm", "cut myself", "better off dead",
            "don't want to live", "do not want to live", "no reason to live", "end it all",
            // de
            "suizid", "selbstmord", "selbstmordgedanken", "mich umbringen", "mir das leben nehmen", "will sterben",
            "möchte sterben", "mich töten", "mir etwas antun", "mir was antun", "mich selbst verletzen",
            "selbstverletzung", "mich ritzen", "lebensmüde", "nicht mehr leben will", "nicht mehr leben möchte",
            "nicht mehr leben kann", "keinen sinn mehr zu leben", "will nicht mehr leben", "möchte nicht mehr leben");

    public static boolean indicatesCrisis(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = normalize(text);
        for (String phrase : CRISIS_PHRASES) {
            if (t.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ explicit requests

    private static final Pattern EXPLICIT_REQUEST = compile(
            "(?:^|[.!?,;] ?|kannst du |k[oö]nntest du |bitte |magst du )erkl[aä]r(?:e|st|en)?\\b", "was denkst du", "was meinst du", "was h[aä]ltst du", "deine meinung", "deine einsch[aä]tzung",
            "kannst du mir (das )?(sagen|erkl[aä]ren)", "hast du (einen|ein paar) (rat|tipp)", "was r[aä]tst du",
            "gib mir (einen|ein paar) (rat|tipp)", "sag mir,? warum", "was w[uü]rdest du",
            "(?:^|[.!?,;] ?|can you |could you |would you |please |pls )explain\\b", "what do you think", "what is your opinion", "what's your opinion", "your opinion", "your thoughts",
            "what would you do", "any advice", "any tips", "give me (some )?(advice|a tip)", "your advice",
            "tell me why", "(can|could) you tell me", "what do you suggest");

    /** True only if the user's latest answer explicitly asks for an explanation, opinion or advice (rule 3). */
    public static boolean asksForExplanation(String userText) {
        return userText != null && EXPLICIT_REQUEST.matcher(normalize(userText)).find();
    }

    private static final Pattern DONE = compile(
            "das war'?s", "das ist alles", "mehr gibt es nicht", "ich bin fertig", "ich bin durch", "lass uns aufh[oö]ren",
            "genug f[uü]r heute", "ich (m[oö]chte|will) aufh[oö]ren", "mehr f[aä]llt mir nicht ein", "nichts mehr zu sagen",
            "that'?s all", "that is all", "i'?m done", "i am done", "nothing more to (say|add)", "nothing else to (say|add)",
            "that'?s it for (today|me|now)", "let'?s (stop|wrap up)", "enough for today", "i want to stop", "no more questions");

    /** True if the user says they are done; this is the only way (besides exhausted questions) CLOSING is allowed. */
    public static boolean signalsDone(String userText) {
        return userText != null && DONE.matcher(normalize(userText)).find();
    }

    // ------------------------------------------------------------------ semantic checks

    private static final String SS = "(?:^|[.!?:;]\\s+)";
    private static final Pattern PRAISE = compile(
            // sentence-initial interjections with punctuation
            SS + "(?:great|wonderful|excellent|awesome|fantastic|amazing|perfect|brilliant|well done|bravo|toll|super|gro(?:ß|ss)artig|wunderbar|prima|klasse|fantastisch|ausgezeichnet|sehr gut)\\s*[!,.]",
            // en
            "\\b(?:great|good|nice|excellent|fantastic|wonderful|brilliant) (?:insight|job|point|question|observation|reflection|answer|work|thinking|idea)\\b",
            "\\bwell done\\b",
            "\\b(?:that's|that is|this is) (?:a |an )?(?:really |truly |so |very )?(?:great|wonderful|amazing|fantastic|excellent|brilliant|insightful)\\b",
            "\\bi'?m (?:so )?proud of you\\b",
            "\\byou(?:'re| are) (?:doing (?:so |really )?(?:great|well)|so (?:smart|wise|brave|insightful))\\b",
            "\\bhow (?:wonderful|great|amazing)\\b",
            "\\bgood for you\\b", "\\bkeep it up\\b", "\\bwhat an? (?:great|wonderful|amazing|insightful)\\b",
            "\\b(?:very|so) insightful\\b", "\\bnicely (?:said|put)\\b",
            // de
            "\\b(?:gro(?:ß|ss)artige[rsnm]?|tolle[rsnm]?|wunderbare[rsnm]?|ausgezeichnete[rsnm]?|gute[rsnm]?|kluge[rsnm]?) (?:einsicht|idee|frage|erkenntnis|antwort|reflexion|beobachtung|gedanke|gedanken|arbeit|punkt)\\b",
            "\\b(?:gut|toll|super|prima|klasse|gro(?:ß|ss)artig) gemacht\\b",
            "\\b(?:das|dies) ist (?:ja |wirklich |echt |ganz |sehr |so )?(?:toll|super|gro(?:ß|ss)artig|wunderbar|fantastisch|klasse|prima|bewundernswert|beeindruckend)\\b",
            "\\bwie (?:toll|wunderbar|gro(?:ß|ss)artig)\\b",
            "\\bich bin stolz auf dich\\b", "\\bweiter so\\b", "\\bsehr (?:gut|klug|weise) (?:gesagt|beobachtet|erkannt)\\b",
            "\\bdu machst das (?:toll|super|gro(?:ß|ss)artig|gut)\\b");

    private static final Pattern DIAGNOSIS_SCORE = compile(
            "\\bdiagnos\\w*",
            "\\b(?:your|dein(?:e|en|em|er)?) (?:score|punktzahl|punkte|bewertung|rating)\\b",
            "\\b\\d+\\s*(?:/|von|out of)\\s*10\\b",
            "\\b(?:du|you) (?:hast|leidest an|have|suffer from|are suffering from) (?:eine |an |a )?(?:depression|depressionen|angstst[oö]rung|burnout|anxiety|ptsd|ptbs|borderline)\\b",
            "\\b(?:you (?:need|should (?:see|get|try))|du (?:brauchst|solltest)) (?:a |an |eine[nr]? )?(?:therapy|therapist|therapie|therapeut\\w*)");

    public static boolean containsPraise(String text) {
        return text != null && PRAISE.matcher(normalize(text)).find();
    }

    public static boolean containsDiagnosisOrScore(String text) {
        return text != null && DIAGNOSIS_SCORE.matcher(normalize(text)).find();
    }

    public static int wordCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.strip().split("\\s+").length;
    }

    public static int questionMarks(String text) {
        int n = 0;
        for (int i = 0; text != null && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '?' || c == '？') {
                n++;
            }
        }
        return n;
    }

    /** True if the text, ignoring trailing quotes, brackets and whitespace, ends with a question mark. */
    public static boolean endsWithQuestion(String text) {
        if (text == null) {
            return false;
        }
        int end = text.length();
        while (end > 0 && "\"'»«”“’)] \t\r\n".indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return end > 0 && (text.charAt(end - 1) == '?' || text.charAt(end - 1) == '？');
    }

    /** Checks common to every coach text. Problem texts never contain the checked text. */
    public static List<String> commonProblems(String text) {
        List<String> problems = new ArrayList<>();
        if (wordCount(text) > MAX_WORDS) {
            problems.add("the text must be at most " + MAX_WORDS + " words");
        }
        if (containsPraise(text)) {
            problems.add("no generic praise or flattery (for example 'great insight', 'well done', 'toll', 'großartig')");
        }
        if (containsDiagnosisOrScore(text)) {
            problems.add("no diagnoses, therapy claims, scores or ratings");
        }
        return problems;
    }

    /** One question per turn: exactly one question mark, and it must be a question, not several. */
    public static List<String> singleQuestionProblems(String text) {
        List<String> problems = new ArrayList<>();
        int marks = questionMarks(text);
        if (marks == 0) {
            problems.add("the text must be a question and contain a question mark");
        } else if (marks > 1) {
            problems.add("ask exactly one question at a time");
        }
        return problems;
    }

    // ------------------------------------------------------------------ helpers

    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replace('’', '\'').replace('‘', '\'').replace('´', '\'').replace('`', '\'')
                .replaceAll("\\s+", " ");
    }

    private static Pattern compile(String... alternatives) {
        return Pattern.compile("(?:" + String.join("|", alternatives) + ")", Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE);
    }
}
