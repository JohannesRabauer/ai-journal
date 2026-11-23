package dev.rabauer.ai.diary.shell;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.settings.SettingsDto;
import dev.rabauer.ai.diary.client.SettingsClient;
import org.springframework.stereotype.Component;

/** Sets each new UI's locale from settings.uiLanguage (AUTO or backend unreachable: browser locale). */
@Component
public class UiLocaleInitializer implements VaadinServiceInitListener {

    private final SettingsClient settings;

    public UiLocaleInitializer(SettingsClient settings) {
        this.settings = settings;
    }

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addUIInitListener(e -> {
            Language language = Language.AUTO;
            try {
                SettingsDto s = settings.get();
                if (s != null && s.uiLanguage() != null) {
                    language = s.uiLanguage();
                }
            } catch (RuntimeException ignored) {
                // backend unreachable: fall back to the browser locale; the shell shows the offline notice
            }
            e.getUI().setLocale(UiLocaleResolver.resolve(language, e.getUI().getLocale()));
        });
    }
}
