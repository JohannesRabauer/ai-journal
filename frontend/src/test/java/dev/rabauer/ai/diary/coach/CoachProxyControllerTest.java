package dev.rabauer.ai.diary.coach;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CoachProxyControllerTest {

    private MockRestServiceServer backend;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend");
        backend = MockRestServiceServer.bindTo(builder).build();
        mvc = MockMvcBuilders.standaloneSetup(new CoachProxyController(builder.build())).build();
    }

    @Test
    void ttsForwardsJsonAndReturnsAudio() throws Exception {
        byte[] audio = {1, 2, 3};
        backend.expect(requestTo("http://backend/api/tts")).andExpect(method(HttpMethod.POST))
                .andExpect(MockRestRequestMatchers.header("Content-Type", "application/json"))
                .andExpect(MockRestRequestMatchers.content().json("{\"text\":\"Hallo\",\"language\":\"DE\"}"))
                .andRespond(withSuccess(audio, MediaType.parseMediaType("audio/wav")));
        mvc.perform(post("/upload/tts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Hallo\",\"language\":\"DE\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "audio/wav"))
                .andExpect(content().bytes(audio));
        backend.verify();
    }

    @Test
    void tts204IsPassedThroughSoTheClientStaysSilent() throws Exception {
        backend.expect(requestTo("http://backend/api/tts")).andRespond(withStatus(HttpStatus.NO_CONTENT));
        mvc.perform(post("/upload/tts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void transcribeForwardsRawAudioWithLanguage() throws Exception {
        byte[] audio = {9, 8, 7, 6};
        backend.expect(requestTo("http://backend/api/transcribe?language=DE")).andExpect(method(HttpMethod.POST))
                .andExpect(MockRestRequestMatchers.header("Content-Type", "audio/webm"))
                .andExpect(MockRestRequestMatchers.content().bytes(audio))
                .andRespond(withSuccess("{\"text\":\"Hallo\",\"detectedLanguage\":\"DE\"}", MediaType.APPLICATION_JSON));
        mvc.perform(post("/upload/transcribe?language=de").contentType("audio/webm").content(audio))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"text\":\"Hallo\",\"detectedLanguage\":\"DE\"}"));
        backend.verify();
    }

    @Test
    void transcribeDefaultsToAutoAndRejectsOddLanguageValues() throws Exception {
        backend.expect(requestTo("http://backend/api/transcribe?language=AUTO"))
                .andRespond(withSuccess("{\"text\":\"\"}", MediaType.APPLICATION_JSON));
        mvc.perform(post("/upload/transcribe").contentType("audio/webm").content(new byte[]{1}))
                .andExpect(status().isOk());
        backend.reset();
        backend.expect(requestTo("http://backend/api/transcribe?language=AUTO"))
                .andRespond(withSuccess("{\"text\":\"\"}", MediaType.APPLICATION_JSON));
        mvc.perform(post("/upload/transcribe?language=xx%26evil%3D1").contentType("audio/webm").content(new byte[]{1}))
                .andExpect(status().isOk());
        backend.verify();
    }

    @Test
    void backendErrorStatusAndBodyArePassedThrough() throws Exception {
        backend.expect(requestTo("http://backend/api/transcribe?language=AUTO"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"STT_UNAVAILABLE\",\"message\":\"Not available.\"}"));
        mvc.perform(post("/upload/transcribe").contentType("audio/webm").content(new byte[]{1}))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("STT_UNAVAILABLE")));
    }

    @Test
    void unreachableBackendBecomesCalm502WithoutRequestDetails() throws Exception {
        backend.expect(requestTo("http://backend/api/tts")).andRespond(withException(new SocketTimeoutException("boom")));
        mvc.perform(post("/upload/tts").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"geheim\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(containsString("BACKEND_UNREACHABLE")))
                .andExpect(content().string(not(containsString("geheim"))));
    }
}
