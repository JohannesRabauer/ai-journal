package dev.rabauer.ai.diary.shell;

import com.vaadin.flow.i18n.I18NProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** Vaadin I18NProvider backed by the merged per-feature bundles (de, en). */
@Component
public class DiaryI18NProvider implements I18NProvider {

    private final MessageCatalog catalog = MessageCatalog.fromClasspath(DiaryI18NProvider.class.getClassLoader());

    @Override
    public List<Locale> getProvidedLocales() {
        return List.of(Locale.ENGLISH, Locale.GERMAN);
    }

    @Override
    public String getTranslation(String key, Locale locale, Object... params) {
        return catalog.get(locale, key, params);
    }
}
