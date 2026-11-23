package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.transcription.TranscriptionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TranscriptionsClientTest {

    private static final String JOB = "{\"id\":3,\"entryId\":7,\"recordingId\":\"11111111-2222-3333-4444-555555555555\","
            + "\"status\":\"%s\",\"requestedLanguage\":\"AUTO\",\"attempts\":1,\"errorCode\":%s}";

    private MockRestServiceServer server;
    private TranscriptionsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TranscriptionsClient(builder.build(), new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    void startPostsLanguageAndDefaultsToAuto() {
        server.expect(requestTo("http://backend/api/entries/7/transcriptions")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"language\":\"AUTO\"}"))
                .andRespond(withSuccess(JOB.formatted("QUEUED", "null"), MediaType.APPLICATION_JSON));
        assertThat(client.start(7, null).status()).isEqualTo(TranscriptionStatus.QUEUED);
        server.verify();
    }

    @Test
    void latestReturnsJobWithErrorCode() {
        server.expect(requestTo("http://backend/api/entries/7/transcriptions/latest"))
                .andRespond(withSuccess(JOB.formatted("FAILED", "\"PROVIDER_UNREACHABLE\""), MediaType.APPLICATION_JSON));
        var job = client.latest(7).orElseThrow();
        assertThat(job.status()).isEqualTo(TranscriptionStatus.FAILED);
        assertThat(job.errorCode()).isEqualTo("PROVIDER_UNREACHABLE");
    }

    @Test
    void latestIsEmptyWhileNoJobExists() {
        server.expect(requestTo("http://backend/api/entries/7/transcriptions/latest"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NOT_FOUND\",\"message\":\"No transcription yet.\"}"));
        assertThat(client.latest(7)).isEmpty();
    }

    @Test
    void conflictOnStartIsReportedWithUserMessage() {
        server.expect(requestTo("http://backend/api/entries/7/transcriptions"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"JOB_RUNNING\",\"message\":\"Transcription is already running.\"}"));
        assertThatThrownBy(() -> client.start(7, Language.DE)).isInstanceOfSatisfying(BackendException.class,
                ex -> assertThat(ex.error().message()).isEqualTo("Transcription is already running."));
    }

    @Test
    void otherErrorsPropagate() {
        server.expect(requestTo("http://backend/api/entries/7/transcriptions/latest"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("<html>"));
        assertThatThrownBy(() -> client.latest(7)).isInstanceOf(BackendException.class);
    }
}
