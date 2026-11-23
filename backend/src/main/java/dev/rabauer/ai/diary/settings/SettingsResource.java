package dev.rabauer.ai.diary.settings;

import dev.rabauer.ai.diary.api.settings.SettingsDto;
import dev.rabauer.ai.diary.rest.SettingsApi;
import jakarta.inject.Inject;

public class SettingsResource implements SettingsApi {

    @Inject
    SettingsService service;

    @Override
    public SettingsDto get() {
        return service.get();
    }

    @Override
    public SettingsDto update(SettingsDto settings) {
        return service.update(settings);
    }
}
