package dev.rabauer.ai.diary.system;

import dev.rabauer.ai.diary.api.system.SystemInfoDto;
import dev.rabauer.ai.diary.config.Capability;
import dev.rabauer.ai.diary.config.JournalConfig;
import dev.rabauer.ai.diary.config.ProviderProducers;
import dev.rabauer.ai.diary.config.ProviderSelection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one place that decides whether the app runs in cloud mode (an external provider is selected or the profile is
 * lite-cloud). Used by {@code GET /api/system/info} and by the page shell, so both always agree.
 */
@ApplicationScoped
public class SystemInfoService {

    public static final String CLOUD_NOTICE = "Cloud mode: your entry text and audio are sent to OpenAI for transcription, "
            + "questions, embeddings and speech. Data leaves this device.";

    @Inject
    JournalConfig config;
    @Inject
    ProviderProducers providers;

    public SystemInfoDto info() {
        Map<String, String> names = new LinkedHashMap<>();
        boolean cloud = JournalConfig.PROFILE_LITE_CLOUD.equals(config.profile());
        for (Map.Entry<Capability, ProviderSelection> e : providers.selections().entrySet()) {
            names.put(e.getKey().key(), e.getValue().name());
            cloud |= e.getValue().external();
        }
        return new SystemInfoDto(config.profile(), cloud, cloud ? CLOUD_NOTICE : null, names);
    }

    public boolean cloud() {
        return info().cloudMode();
    }
}
