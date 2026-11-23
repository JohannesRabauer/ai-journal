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

    @Bean
    RestClient backendRestClient(RestClient.Builder builder, @Value("${backend.url}") String backendUrl) {
        return builder.baseUrl(backendUrl).build();
    }
}
