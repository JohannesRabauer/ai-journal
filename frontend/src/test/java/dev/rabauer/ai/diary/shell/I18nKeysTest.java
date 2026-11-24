package dev.rabauer.ai.diary.shell;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Accessibility safety net (docs/A11Y.md): every translation key a view passes as a literal to
 * {@code getTranslation(..)} / {@code texts.get(..)} (labels, aria-labels, headings, status texts) exists in both
 * bundles with a non-blank text, so no control ends up with a missing accessible name in either language.
 */
class I18nKeysTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Pattern LITERAL_KEY =
            Pattern.compile("(?:getTranslation|texts\\.get|translate\\.apply)\\(\\s*\"([a-z][A-Za-z0-9]*(?:\\.[A-Za-z0-9-]+)+)\"");

    private final MessageCatalog catalog = MessageCatalog.fromClasspath(getClass().getClassLoader());

    @Test
    void everyLiteralKeyUsedInJavaExistsInBothLanguages() throws IOException {
        TreeSet<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = LITERAL_KEY.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    String key = m.group(1);
                    for (String lang : MessageCatalog.LANGUAGES) {
                        String text = catalog.get(Locale.forLanguageTag(lang), key);
                        if (text.equals(key) || text.isBlank()) {
                            missing.add(lang + " " + key + " (" + file.getFileName() + ")");
                        }
                    }
                }
            }
        }
        assertThat(missing).as("keys without a text").isEmpty();
    }

    @Test
    void recorderAndVoiceLabelsExistInBothLanguages() {
        TreeSet<String> missing = new TreeSet<>();
        for (String lang : MessageCatalog.LANGUAGES) {
            Locale locale = Locale.forLanguageTag(lang);
            for (String key : java.util.List.of("record", "pause", "resume", "stop", "elapsed", "level", "idle",
                    "requesting", "recording", "paused", "stopping", "uploading", "pending", "waiting", "done",
                    "retry", "recoverTitle", "recoverText", "finish")) {
                check(missing, lang, "entries.recorder." + key, catalog.get(locale, "entries.recorder." + key));
            }
            for (String key : java.util.List.of("record", "stop", "cancel", "elapsed", "requesting", "recording",
                    "transcribing", "done", "cancelled", "error.mic-denied", "error.unsupported",
                    "error.unavailable", "error.too-large", "error.empty", "error.failed")) {
                check(missing, lang, "coach.voice." + key, catalog.get(locale, "coach.voice." + key));
            }
            for (String key : java.util.List.of("coach.speech.stop", "coach.transcript.label", "coach.play.label",
                    "nav.skip", "nav.label")) {
                check(missing, lang, key, catalog.get(locale, key));
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void germanAndEnglishBundlesHaveTheSameKeys() throws IOException {
        for (String feature : MessageCatalog.FEATURES) {
            Map<String, String> en = keys(feature, "en");
            Map<String, String> de = keys(feature, "de");
            assertThat(de.keySet()).as(feature + " de vs en").isEqualTo(en.keySet());
            en.forEach((k, v) -> assertThat(v).as("en " + k).isNotBlank());
            de.forEach((k, v) -> assertThat(v).as("de " + k).isNotBlank());
        }
    }

    private static void check(TreeSet<String> missing, String lang, String key, String text) {
        if (text.equals(key) || text.isBlank()) {
            missing.add(lang + " " + key);
        }
    }

    private static Map<String, String> keys(String feature, String lang) throws IOException {
        Path file = Path.of("src/main/resources/i18n/" + feature + "_" + lang + ".properties");
        java.util.Properties p = new java.util.Properties();
        if (Files.exists(file)) {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(reader);
            }
        }
        Map<String, String> out = new java.util.TreeMap<>();
        p.forEach((k, v) -> out.put(k.toString(), v.toString()));
        return out;
    }
}
