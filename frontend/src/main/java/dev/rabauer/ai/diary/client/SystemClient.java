package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.system.SystemInfoDto;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Typed client for {@code /api/system} (backend SystemApi). */
@Component
public class SystemClient {

    private final RestClient client;
    private final ObjectMapper mapper;

    public SystemClient(RestClient backendRestClient, ObjectMapper mapper) {
        this.client = backendRestClient;
        this.mapper = mapper;
    }

    public SystemInfoDto info() {
        return client.get().uri("/api/system/info").retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(SystemInfoDto.class);
    }
}
