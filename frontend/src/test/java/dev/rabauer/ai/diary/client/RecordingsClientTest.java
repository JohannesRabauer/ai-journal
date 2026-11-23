package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.rabauer.ai.diary.api.audio.CompleteRecordingRequest;
import dev.rabauer.ai.diary.api.audio.RecordingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RecordingsClientTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String DTO = "{\"id\":\"" + ID + "\",\"entryId\":7,\"mimeType\":\"audio/webm\",\"status\":\"%s\","
            + "\"receivedChunks\":[0,1],\"totalChunks\":2,\"durationMs\":20000,\"sizeBytes\":10}";

    private MockRestServiceServer server;
    private RecordingsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RecordingsClient(builder.build(), new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    void getParsesStatusWithReceivedChunks() {
        server.expect(requestTo("http://backend/api/recordings/" + ID))
                .andRespond(withSuccess(DTO.formatted("RECORDING"), MediaType.APPLICATION_JSON));
        var dto = client.get(ID);
        assertThat(dto.receivedChunks()).containsExactly(0, 1);
        assertThat(dto.status()).isEqualTo(RecordingStatus.RECORDING);
    }

    @Test
    void completePostsChunkCountAndDuration() {
        server.expect(requestTo("http://backend/api/recordings/" + ID + "/complete")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"totalChunks\":2,\"durationMs\":20000}"))
                .andRespond(withSuccess(DTO.formatted("COMPLETE"), MediaType.APPLICATION_JSON));
        assertThat(client.complete(ID, new CompleteRecordingRequest(2, 20000)).status()).isEqualTo(RecordingStatus.COMPLETE);
        server.verify();
    }

    @Test
    void deleteAudioKeepsEntry() {
        server.expect(requestTo("http://backend/api/recordings/" + ID + "/audio")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess(DTO.formatted("AUDIO_DELETED"), MediaType.APPLICATION_JSON));
        assertThat(client.deleteAudio(ID).status()).isEqualTo(RecordingStatus.AUDIO_DELETED);
    }

    @Test
    void missingChunksConflictBecomesBackendException() {
        server.expect(requestTo("http://backend/api/recordings/" + ID + "/complete"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"CHUNKS_MISSING\",\"message\":\"Some parts of the recording are missing.\"}"));
        assertThatThrownBy(() -> client.complete(ID, new CompleteRecordingRequest(3, 1)))
                .isInstanceOfSatisfying(BackendException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(409);
                    assertThat(ex.error().code()).isEqualTo("CHUNKS_MISSING");
                });
    }
}
