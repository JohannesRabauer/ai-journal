package dev.rabauer.ai.diary.shell;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class MessageCatalogTest {

    @TempDir
    Path dir;

    private URL write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p.toUri().toURL();
    }

    private MessageCatalog catalog(Map<String, List<URL>> files, String... features) {
        return new MessageCatalog(name -> files.getOrDefault(name, List.of()), List.of(features));
    }

    @Test
    void mergesBundlesOfAllFeaturesAndToleratesMissingFiles() throws IOException {
        Map<String, List<URL>> files = new HashMap<>();
        files.put("i18n/shell_en.properties", List.of(write("a/shell_en.properties", "nav.today=Today")));
        files.put("i18n/settings_en.properties", List.of(write("b/settings_en.properties", "settings.title=Settings")));
        // gym has no files at all
        MessageCatalog c = catalog(files, "shell", "settings", "gym");
        assertThat(c.get(Locale.ENGLISH, "nav.today")).isEqualTo("Today");
        assertThat(c.get(Locale.ENGLISH, "settings.title")).isEqualTo("Settings");
    }

    @Test
    void usesRequestedLanguageThenEnglishThenKey() throws IOException {
        Map<String, List<URL>> files = new HashMap<>();
        files.put("i18n/shell_en.properties", List.of(write("shell_en.properties", "a=A en\nb=B en")));
        files.put("i18n/shell_de.properties", List.of(write("shell_de.properties", "a=A de")));
        MessageCatalog c = catalog(files, "shell");
        assertThat(c.get(Locale.GERMAN, "a")).isEqualTo("A de");
        assertThat(c.get(Locale.GERMAN, "b")).isEqualTo("B en");
        assertThat(c.get(Locale.GERMAN, "missing.key")).isEqualTo("missing.key");
        assertThat(c.get(Locale.FRENCH, "a")).isEqualTo("A en");
        assertThat(c.get(null, "a")).isEqualTo("A en");
    }

    @Test
    void readsUtf8AndFormatsParameters() throws IOException {
        Map<String, List<URL>> files = new HashMap<>();
        files.put("i18n/shell_de.properties", List.of(write("shell_de.properties", "greet=Grüße {0} aus {1}")));
        MessageCatalog c = catalog(files, "shell");
        assertThat(c.get(Locale.GERMAN, "greet", "Anna", "Köln")).isEqualTo("Grüße Anna aus Köln");
    }

    @Test
    void laterFeatureAndLaterJarOverrideEarlierOnes() throws IOException {
        Map<String, List<URL>> files = new HashMap<>();
        files.put("i18n/shell_en.properties", List.of(write("x/shell_en.properties", "k=first"), write("y/shell_en.properties", "k=second")));
        assertThat(catalog(files, "shell").get(Locale.ENGLISH, "k")).isEqualTo("second");
    }

    @Test
    void realBundlesHaveTheSameKeysInEnglishAndGermanAndCoverNavigation() throws IOException {
        ClassLoader cl = getClass().getClassLoader();
        for (String feature : List.of("shell", "settings")) {
            Set<String> en = keys(cl, "i18n/" + feature + "_en.properties");
            Set<String> de = keys(cl, "i18n/" + feature + "_de.properties");
            assertThat(en).as(feature + " not empty").isNotEmpty();
            assertThat(de).as(feature + " keys de == en").isEqualTo(en);
        }
        MessageCatalog c = MessageCatalog.fromClasspath(cl);
        for (NavDestination d : NavDestination.ALL) {
            assertThat(c.get(Locale.ENGLISH, d.labelKey())).isNotEqualTo(d.labelKey());
            assertThat(c.get(Locale.GERMAN, d.labelKey())).isNotEqualTo(d.labelKey());
        }
        assertThat(c.get(Locale.GERMAN, "nav.today")).isEqualTo("Heute");
        assertThat(c.get(Locale.ENGLISH, "nav.gym")).isEqualTo("Thinking Gym");
    }

    private static Set<String> keys(ClassLoader cl, String name) throws IOException {
        Enumeration<URL> e = cl.getResources(name);
        Set<String> out = new TreeSet<>();
        while (e.hasMoreElements()) {
            Properties p = new Properties();
            try (var r = new java.io.InputStreamReader(e.nextElement().openStream(), StandardCharsets.UTF_8)) {
                p.load(r);
            }
            p.keySet().forEach(k -> out.add(k.toString()));
        }
        return out;
    }
}
