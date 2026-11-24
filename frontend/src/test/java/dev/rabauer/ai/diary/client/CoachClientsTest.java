package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.rabauer.ai.diary.api.coach.AnswerRequest;
import dev.rabauer.ai.diary.api.coach.CoachMessageKind;
import dev.rabauer.ai.diary.api.coach.ConversationStatus;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.tts.SpeakRequest;
import dev.rabauer.ai.diary.api.transcription.TranscribeResultDto;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CoachClientsTest {

    private static final String CONVERSATION = "{\"id\":5,\"entryId\":7,\"status\":\"%s\",\"language\":\"DE\","
            + "\"messages\":[{\"id\":1,\"role\":\"COACH\",\"kind\":\"OPENING_QUESTION\",\"content\":\"Was war wichtig?\","
            + "\"voice\":false,\"createdAt\":\"2026-01-02T10:00:00Z\"}],\"startedAt\":\"2026-01-02T10:00:00Z\","
            + "\"updatedAt\":\"2026-01-02T10:00:00Z\",\"endedAt\":null}";

    private MockRestServiceServer server;
    private CoachClient coach;
    private TtsClient tts;
    private TranscribeClient transcribe;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend");
        server = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RestClient rest = builder.build();
        coach = new CoachClient(rest, mapper);
        tts = new TtsClient(rest, mapper);
        transcribe = new TranscribeClient(rest, mapper);
    }

    @Test
    void forEntryParsesListAndEmptyList() {
        server.expect(requestTo("http://backend/api/entries/7/conversations")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[" + CONVERSATION.formatted("PAUSED") + "]", MediaType.APPLICATION_JSON));
        var list = coach.forEntry(7);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).status()).isEqualTo(ConversationStatus.PAUSED);
        assertThat(list.get(0).messages().get(0).kind()).isEqualTo(CoachMessageKind.OPENING_QUESTION);
        server.reset();

        server.expect(requestTo("http://backend/api/entries/8/conversations"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThat(coach.forEntry(8)).isEmpty();
        server.verify();
    }

    @Test
    void startPostsEntryId() {
        server.expect(requestTo("http://backend/api/conversations")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"entryId\":7}"))
                .andRespond(withSuccess(CONVERSATION.formatted("ACTIVE"), MediaType.APPLICATION_JSON));
        assertThat(coach.start(7).id()).isEqualTo(5);
        server.verify();
    }

    @Test
    void answerPostsTextAndVoiceFlag() {
        server.expect(requestTo("http://backend/api/conversations/5/answers")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"text\":\"Ja\",\"voice\":true}"))
                .andRespond(withSuccess(CONVERSATION.formatted("ACTIVE"), MediaType.APPLICATION_JSON));
        assertThat(coach.answer(5, new AnswerRequest("Ja", true)).status()).isEqualTo(ConversationStatus.ACTIVE);
        server.verify();
    }

    @Test
    void pauseResumeEndPostToTheirPaths() {
        for (String action : new String[]{"pause", "resume", "end"}) {
            server.expect(requestTo("http://backend/api/conversations/5/" + action)).andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(CONVERSATION.formatted("ENDED"), MediaType.APPLICATION_JSON));
        }
        coach.pause(5);
        coach.resume(5);
        coach.end(5);
        server.verify();
    }

    @Test
    void errorBodyBecomesBackendException() {
        server.expect(requestTo("http://backend/api/conversations/5/answers"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"PROVIDER_UNAVAILABLE\",\"message\":\"The language model is not reachable.\"}"));
        assertThatThrownBy(() -> coach.answer(5, new AnswerRequest("x", false)))
                .isInstanceOfSatisfying(BackendException.class, e -> {
                    assertThat(e.status()).isEqualTo(502);
                    assertThat(e.error().code()).isEqualTo("PROVIDER_UNAVAILABLE");
                });
    }

    @Test
    void notImplementedBackendIsAnErrorNotAnEmptyResult() {
        server.expect(requestTo("http://backend/api/conversations/5"))
                .andRespond(withStatus(HttpStatus.NOT_IMPLEMENTED));
        assertThatThrownBy(() -> coach.get(5)).isInstanceOfSatisfying(BackendException.class,
                e -> assertThat(e.status()).isEqualTo(501));
    }

    @Test
    void ttsSpeakReturnsBytesAnd204MeansSilent() {
        server.expect(requestTo("http://backend/api/tts")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"text\":\"Hallo\",\"language\":\"DE\",\"voice\":null}"))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.parseMediaType("audio/wav")));
        assertThat(tts.speak(new SpeakRequest("Hallo", Language.DE, null))).contains(new byte[]{1, 2, 3});
        server.reset();

        server.expect(requestTo("http://backend/api/tts")).andRespond(withStatus(HttpStatus.NO_CONTENT));
        assertThat(tts.speak(new SpeakRequest("Hallo", Language.DE, null))).isEmpty();
        server.verify();
    }

    @Test
    void ttsVoicesParsed() {
        server.expect(requestTo("http://backend/api/tts/voices"))
                .andRespond(withSuccess("[{\"id\":\"de_x\",\"language\":\"DE\",\"label\":\"Thorsten\"}]",
                        MediaType.APPLICATION_JSON));
        assertThat(tts.voices()).extracting("id").containsExactly("de_x");
    }

    @Test
    void transcribeSendsRawAudioWithLanguageQuery() {
        server.expect(requestTo("http://backend/api/transcribe?language=DE")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "audio/webm"))
                .andExpect(content().bytes(new byte[]{9, 8, 7}))
                .andRespond(withSuccess("{\"text\":\"Hallo\",\"detectedLanguage\":\"DE\"}", MediaType.APPLICATION_JSON));
        TranscribeResultDto r = transcribe.transcribe(new byte[]{9, 8, 7}, "audio/webm", Language.DE);
        assertThat(r.text()).isEqualTo("Hallo");
        server.verify();
    }

    @Test
    void transcribeUnavailableSurfacesErrorCode() {
        server.expect(requestTo("http://backend/api/transcribe?language=AUTO"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"STT_UNAVAILABLE\",\"message\":\"Speech recognition is not available.\"}"));
        assertThatThrownBy(() -> transcribe.transcribe(new byte[]{1}, null, null))
                .isInstanceOfSatisfying(BackendException.class, e -> assertThat(e.status()).isEqualTo(503));
    }
}
