package dev.rabauer.ai.diary.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BackendClientConfigTest {

    @Test
    void everyBackendCallCarriesTheWriteGuardHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = new BackendClientConfig().backendRestClient(builder, "http://backend");
        server.expect(requestTo("http://backend/api/x")).andExpect(header("X-AI-Diary", "1"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        client.post().uri("/api/x").retrieve().toBodilessEntity();
        server.verify();
    }
}
