package dev.rabauer.ai.diary.shell;

import dev.rabauer.ai.diary.api.common.Language;

import java.util.Locale;

/** UI locale: the stored setting unless AUTO, else the browser locale; only de and en are supported. */
public final class UiLocaleResolver {

    private UiLocaleResolver() {
    }

    public static Locale resolve(Language uiLanguage, Locale browser) {
        if (uiLanguage == Language.DE) {
            return Locale.GERMAN;
        }
        if (uiLanguage == Language.EN) {
            return Locale.ENGLISH;
        }
        return browser != null && Locale.GERMAN.getLanguage().equals(browser.getLanguage()) ? Locale.GERMAN : Locale.ENGLISH;
    }
}
