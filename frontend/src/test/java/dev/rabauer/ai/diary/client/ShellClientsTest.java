package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.privacy.DeleteAllRequest;
import dev.rabauer.ai.diary.api.settings.SettingsDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ShellClientsTest {

    private MockRestServiceServer server;
    private SystemClient system;
    private SettingsClient settings;
    private PrivacyClient privacy;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend");
        server = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RestClient rc = builder.build();
        system = new SystemClient(rc, mapper);
        settings = new SettingsClient(rc, mapper);
        privacy = new PrivacyClient(rc, mapper);
    }

    @Test
    void systemInfoParsesCloudModeAndProviders() {
        server.expect(requestTo("http://backend/api/system/info"))
                .andRespond(withSuccess("{\"profile\":\"lite-cloud\",\"cloudMode\":true,\"cloudNotice\":\"Sent to OpenAI\",\"providers\":{\"stt\":\"openai\"}}",
                        MediaType.APPLICATION_JSON));
        var info = system.info();
        assertThat(info.cloudMode()).isTrue();
        assertThat(info.cloudNotice()).isEqualTo("Sent to OpenAI");
        assertThat(info.providers()).containsEntry("stt", "openai");
        server.verify();
    }

    @Test
    void settingsGetAndPartialPut() {
        server.expect(requestTo("http://backend/api/settings"))
                .andRespond(withSuccess("{\"uiLanguage\":\"AUTO\",\"defaultEntryLanguage\":\"DE\",\"keepAudio\":true,\"ttsAutoPlay\":false,\"reviewIntervalsDays\":[1,3,7]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://backend/api/settings")).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"keepAudio\":false}"))
                .andRespond(withSuccess("{\"uiLanguage\":\"AUTO\",\"keepAudio\":false,\"reviewIntervalsDays\":[1,3,7]}", MediaType.APPLICATION_JSON));
        assertThat(settings.get().reviewIntervalsDays()).containsExactly(1, 3, 7);
        var saved = settings.update(new SettingsDto(null, null, false, null, null, null, null));
        assertThat(saved.keepAudio()).isFalse();
        assertThat(saved.uiLanguage()).isEqualTo(Language.AUTO);
        server.verify();
    }

    @Test
    void settingsValidationErrorKeepsBackendMessage() {
        server.expect(requestTo("http://backend/api/settings"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"INVALID_INTERVALS\",\"message\":\"Intervals must be ascending.\"}"));
        assertThatThrownBy(() -> settings.update(new SettingsDto(null, null, null, null, null, null, List.of(5, 2))))
                .isInstanceOfSatisfying(BackendException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(400);
                    assertThat(ex.error().message()).isEqualTo("Intervals must be ascending.");
                });
    }

    @Test
    void exportStreamsZipBytesAndPassesIncludeAudio() {
        server.expect(requestTo("http://backend/api/data/export?includeAudio=true"))
                .andExpect(header("Accept", "application/zip"))
                .andRespond(withSuccess("PK-bytes".getBytes(StandardCharsets.UTF_8), MediaType.parseMediaType("application/zip")));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        privacy.export(true, out);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("PK-bytes");
        server.verify();
    }

    @Test
    void notImplementedIsRecognised() {
        server.expect(requestTo("http://backend/api/data/export?includeAudio=false"))
                .andRespond(withStatus(HttpStatus.NOT_IMPLEMENTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NOT_IMPLEMENTED\",\"message\":\"Not implemented yet.\"}"));
        assertThatThrownBy(() -> privacy.export(false, new ByteArrayOutputStream()))
                .isInstanceOfSatisfying(BackendException.class, ex -> assertThat(PrivacyClient.isNotImplemented(ex)).isTrue());
    }

    @Test
    void deleteAllPostsPhraseAndParsesPerStoreResult() {
        server.expect(requestTo("http://backend/api/data/delete-all")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"confirmation\":\"DELETE EVERYTHING\"}"))
                .andRespond(withSuccess("{\"deleted\":{\"entries\":3,\"audio\":2}}", MediaType.APPLICATION_JSON));
        var result = privacy.deleteAll(new DeleteAllRequest(DeleteAllRequest.CONFIRMATION_PHRASE));
        assertThat(result.deleted()).containsEntry("entries", 3L).containsEntry("audio", 2L);
        server.verify();
    }

    @Test
    void deleteAllNotImplementedWithNonJsonBodyStillMapsStatus() {
        server.expect(requestTo("http://backend/api/data/delete-all")).andRespond(withStatus(HttpStatus.NOT_IMPLEMENTED).body("<html>"));
        assertThatThrownBy(() -> privacy.deleteAll(new DeleteAllRequest(DeleteAllRequest.CONFIRMATION_PHRASE)))
                .isInstanceOfSatisfying(BackendException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(501);
                    assertThat(PrivacyClient.isNotImplemented(ex)).isTrue();
                });
    }
}
