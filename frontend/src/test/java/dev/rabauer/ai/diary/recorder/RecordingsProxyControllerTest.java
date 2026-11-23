package dev.rabauer.ai.diary.recorder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RecordingsProxyControllerTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private MockRestServiceServer backend;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend");
        backend = MockRestServiceServer.bindTo(builder).build();
        mvc = MockMvcBuilders.standaloneSetup(new RecordingsProxyController(builder.build())).build();
    }

    @Test
    void startForwardsJsonBodyAndReturnsBackendAnswer() throws Exception {
        backend.expect(requestTo("http://backend/api/recordings")).andExpect(method(HttpMethod.POST))
                .andExpect(MockRestRequestMatchers.header("Content-Type", "application/json"))
                .andExpect(MockRestRequestMatchers.content().json("{\"mimeType\":\"audio/webm\"}"))
                .andRespond(withSuccess("{\"id\":\"" + ID + "\"}", MediaType.APPLICATION_JSON));
        mvc.perform(post("/upload/recordings").contentType(MediaType.APPLICATION_JSON).content("{\"mimeType\":\"audio/webm\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"id\":\"" + ID + "\"}"));
        backend.verify();
    }

    @Test
    void chunkIsForwardedAsBinaryPutWithIndex() throws Exception {
        byte[] audio = {1, 2, 3, 4, 5};
        backend.expect(requestTo("http://backend/api/recordings/" + ID + "/chunks/4")).andExpect(method(HttpMethod.PUT))
                .andExpect(MockRestRequestMatchers.header("Content-Type", "application/octet-stream"))
                .andExpect(MockRestRequestMatchers.content().bytes(audio))
                .andRespond(withSuccess("{\"chunkIndex\":4,\"duplicate\":false}", MediaType.APPLICATION_JSON));
        mvc.perform(put("/upload/recordings/" + ID + "/chunks/4").contentType(MediaType.APPLICATION_OCTET_STREAM).content(audio))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"chunkIndex\":4,\"duplicate\":false}"));
        backend.verify();
    }

    @Test
    void backendErrorStatusAndBodyArePassedThrough() throws Exception {
        backend.expect(requestTo("http://backend/api/recordings/" + ID + "/complete"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"CHUNKS_MISSING\",\"message\":\"Parts are missing.\"}"));
        mvc.perform(post("/upload/recordings/" + ID + "/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totalChunks\":3,\"durationMs\":1}"))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"code\":\"CHUNKS_MISSING\"}"));
    }

    @Test
    void audioForwardsRangeAndStreamsHeaders() throws Exception {
        byte[] audio = {9, 8, 7};
        backend.expect(requestTo("http://backend/api/recordings/" + ID + "/audio")).andExpect(method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.header("Range", "bytes=0-2"))
                .andRespond(withStatus(HttpStatus.PARTIAL_CONTENT).contentType(MediaType.parseMediaType("audio/webm"))
                        .header("Content-Range", "bytes 0-2/10").header("Accept-Ranges", "bytes").body(audio));
        mvc.perform(get("/upload/recordings/" + ID + "/audio").header("Range", "bytes=0-2"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-2/10"))
                .andExpect(header().string("Content-Type", "audio/webm"))
                .andExpect(content().bytes(audio));
    }

    @Test
    void statusAndDeleteAudioAreForwarded() throws Exception {
        backend.expect(requestTo("http://backend/api/recordings/" + ID)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"receivedChunks\":[0,1]}", MediaType.APPLICATION_JSON));
        backend.expect(requestTo("http://backend/api/recordings/" + ID + "/audio")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{\"status\":\"AUDIO_DELETED\"}", MediaType.APPLICATION_JSON));
        mvc.perform(get("/upload/recordings/" + ID)).andExpect(content().json("{\"receivedChunks\":[0,1]}"));
        mvc.perform(delete("/upload/recordings/" + ID + "/audio")).andExpect(content().json("{\"status\":\"AUDIO_DELETED\"}"));
        backend.verify();
    }

    @Test
    void unreachableBackendYieldsCalmBadGatewayWithoutDetails() throws Exception {
        backend.expect(requestTo("http://backend/api/recordings/" + ID))
                .andRespond(withException(new SocketTimeoutException("secret-detail")));
        mvc.perform(get("/upload/recordings/" + ID))
                .andExpect(status().isBadGateway())
                .andExpect(content().json("{\"code\":\"BACKEND_UNREACHABLE\"}"))
                .andExpect(content().string(not(containsString("secret-detail"))));
    }

    @Test
    void nonUuidIdsAreRejectedBeforeAnyBackendCall() throws Exception {
        mvc.perform(get("/upload/recordings/not-a-uuid")).andExpect(status().isBadRequest());
        backend.verify();
    }
}
