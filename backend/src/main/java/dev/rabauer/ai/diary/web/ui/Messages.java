package dev.rabauer.ai.diary.web.ui;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Texts for server-rendered pages: the same {@code i18n/<feature>_<lang>.properties} files the Vaadin frontend
 * uses (en and de). Used from templates as {@code {t.get('nav.label')}}; the result is HTML-escaped by Qute.
 */
public final class Messages {

    private static final List<String> BUNDLES = List.of("i18n.shell", "i18n.entries", "i18n.memory",
            "i18n.gym", "i18n.weekly", "i18n.settings");

    private final Locale locale;
    private final List<ResourceBundle> bundles;

    private Messages(Locale locale) {
        this.locale = locale;
        this.bundles = BUNDLES.stream().map(b -> ResourceBundle.getBundle(b, locale)).toList();
    }

    /** First supported language of the Accept-Language list (en, de), otherwise English. */
    public static Messages forAcceptable(List<Locale> acceptable) {
        for (Locale l : acceptable) {
            String lang = l.getLanguage();
            if (lang.equals("de") || lang.equals("en")) {
                return new Messages(Locale.forLanguageTag(lang));
            }
        }
        return new Messages(Locale.ENGLISH);
    }

    public Locale locale() {
        return locale;
    }

    public String lang() {
        return locale.getLanguage();
    }

    public String get(String key) {
        for (ResourceBundle b : bundles) {
            try {
                return b.getString(key);
            } catch (MissingResourceException e) {
                // try the next bundle
            }
        }
        return "??" + key + "??";
    }

    public String get(String key, Object a) {
        return new MessageFormat(get(key), locale).format(new Object[] {a});
    }

    public String get(String key, Object a, Object b, Object c) {
        return new MessageFormat(get(key), locale).format(new Object[] {a, b, c});
    }

    public String get(String key, Object a, Object b) {
        return new MessageFormat(get(key), locale).format(new Object[] {a, b});
    }
}
