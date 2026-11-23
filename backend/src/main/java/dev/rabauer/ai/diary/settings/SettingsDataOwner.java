package dev.rabauer.ai.diary.settings;

import dev.rabauer.ai.diary.privacy.ExportSink;
import dev.rabauer.ai.diary.privacy.UserDataOwner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;

@ApplicationScoped
public class SettingsDataOwner implements UserDataOwner {

    @Inject
    SettingsService service;

    @Override
    public String name() {
        return "settings";
    }

    @Override
    public int deleteOrder() {
        return 1500;
    }

    @Override
    public long deleteAll() {
        return service.deleteAll();
    }

    @Override
    public void export(ExportSink sink) throws IOException {
        sink.json("settings.json", service.get());
    }
}
