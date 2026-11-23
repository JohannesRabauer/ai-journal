package dev.rabauer.ai.diary.shell;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Merges all per-feature bundles {@code i18n/<feature>_<lang>.properties} into one catalog per language.
 * Lookup order: requested language, then English, then the key itself. Missing bundle files are tolerated.
 */
public final class MessageCatalog {

    /** Features whose bundles are merged; files that do not exist are skipped. */
    public static final List<String> FEATURES = List.of(
            "shell", "settings", "today", "recorder", "entries", "coach", "memory", "gym", "weekly");
    public static final List<String> LANGUAGES = List.of("en", "de");
    private static final String FALLBACK = "en";

    private final Map<String, Map<String, String>> byLanguage = new HashMap<>();

    /** @param resources resolves a classpath-style name to all matching URLs (one per jar/dir), possibly none */
    public MessageCatalog(Function<String, List<URL>> resources, List<String> features) {
        for (String lang : LANGUAGES) {
            Map<String, String> merged = new HashMap<>();
            for (String feature : features) {
                for (URL url : resources.apply("i18n/" + feature + "_" + lang + ".properties")) {
                    merged.putAll(load(url));
                }
            }
            byLanguage.put(lang, merged);
        }
    }

    public static MessageCatalog fromClasspath(ClassLoader loader) {
        return new MessageCatalog(name -> {
            try {
                Enumeration<URL> e = loader.getResources(name);
                return Collections.list(e);
            } catch (IOException ex) {
                return List.of();
            }
        }, FEATURES);
    }

    public String get(Locale locale, String key, Object... params) {
        String lang = locale == null ? FALLBACK : locale.getLanguage();
        String template = byLanguage.getOrDefault(lang, Map.of()).get(key);
        if (template == null) {
            template = byLanguage.get(FALLBACK).get(key);
        }
        if (template == null) {
            return key;
        }
        if (params == null || params.length == 0) {
            return template;
        }
        return new MessageFormat(template, locale == null ? Locale.ENGLISH : locale).format(params);
    }

    private static Map<String, String> load(URL url) {
        Properties p = new Properties();
        try (InputStream in = url.openStream(); var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException e) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        p.forEach((k, v) -> out.put(k.toString(), v.toString()));
        return out;
    }
}
