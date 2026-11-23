package dev.rabauer.ai.diary.system;

import dev.rabauer.ai.diary.api.system.SystemInfoDto;
import dev.rabauer.ai.diary.config.Capability;
import dev.rabauer.ai.diary.config.JournalConfig;
import dev.rabauer.ai.diary.config.ProviderProducers;
import dev.rabauer.ai.diary.config.ProviderSelection;
import dev.rabauer.ai.diary.rest.SystemApi;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;

public class SystemResource implements SystemApi {

    public static final String CLOUD_NOTICE = "Cloud mode: your entry text and audio are sent to OpenAI for transcription, "
            + "questions, embeddings and speech. Data leaves this device.";

    @Inject
    JournalConfig config;
    @Inject
    ProviderProducers providers;

    @Override
    public SystemInfoDto info() {
        Map<String, String> names = new LinkedHashMap<>();
        boolean cloud = false;
        for (Map.Entry<Capability, ProviderSelection> e : providers.selections().entrySet()) {
            names.put(e.getKey().key(), e.getValue().name());
            cloud |= e.getValue().external();
        }
        cloud |= JournalConfig.PROFILE_LITE_CLOUD.equals(config.profile());
        return new SystemInfoDto(config.profile(), cloud, cloud ? CLOUD_NOTICE : null, names);
    }
}
