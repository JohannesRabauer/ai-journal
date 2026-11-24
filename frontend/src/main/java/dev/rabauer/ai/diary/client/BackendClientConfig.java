package dev.rabauer.ai.diary.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * One RestClient for all backend calls. The browser never talks to the backend directly; every call goes
 * through the frontend server (same origin), so the backend can stay bound to localhost / the compose network.
 */
@Configuration
public class BackendClientConfig {

    /** The backend requires this header on every non-GET request (CSRF guard). */
    public static final String WRITE_GUARD_HEADER = "X-AI-Diary";

    @Bean
    RestClient backendRestClient(RestClient.Builder builder, @Value("${backend.url}") String backendUrl) {
        return builder.baseUrl(backendUrl).defaultHeader(WRITE_GUARD_HEADER, "1").build();
    }
}
