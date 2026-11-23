package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.settings.SettingsDto;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Typed client for {@code /api/settings} (backend SettingsApi). PUT is a partial update: null fields stay unchanged. */
@Component
public class SettingsClient {

    private final RestClient client;
    private final ObjectMapper mapper;

    public SettingsClient(RestClient backendRestClient, ObjectMapper mapper) {
        this.client = backendRestClient;
        this.mapper = mapper;
    }

    public SettingsDto get() {
        return client.get().uri("/api/settings").retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(SettingsDto.class);
    }

    public SettingsDto update(SettingsDto partial) {
        return client.put().uri("/api/settings").contentType(MediaType.APPLICATION_JSON).body(partial).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(SettingsDto.class);
    }
}
