package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.gym.AttemptRequest;
import dev.rabauer.ai.diary.api.gym.GymCategory;
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

class GymClientTest {

    private static final String EXERCISE = "{\"id\":4,\"category\":\"REFRAMING\",\"language\":\"DE\",\"title\":\"Zwei andere Lesarten\","
            + "\"prompt\":\"Wähle ein Ereignis.\",\"sourceExcerpt\":null,\"sourceEntryId\":null,\"steps\":[\"Eins\",\"Zwei\"],"
            + "\"estimatedMinutes\":6,\"personalized\":false}";
    private static final String ATTEMPT = "{\"id\":9,\"exerciseId\":4,\"category\":\"REFRAMING\",\"answer\":\"Meine Antwort\","
            + "\"durationSeconds\":120,\"feedbackQuestions\":%s,\"createdAt\":\"2026-01-02T10:00:00Z\"}";

    private MockRestServiceServer server;
    private GymClient gym;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend");
        server = MockRestServiceServer.bindTo(builder).build();
        gym = new GymClient(builder.build(), new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    void nextPostsWithCategoryAndLanguageHint() {
        server.expect(requestTo("http://backend/api/gym/exercises?category=REFRAMING")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Accept-Language", "de"))
                .andRespond(withSuccess(EXERCISE, MediaType.APPLICATION_JSON));
        var ex = gym.next(GymCategory.REFRAMING, "de");
        assertThat(ex.id()).isEqualTo(4);
        assertThat(ex.language()).isEqualTo(Language.DE);
        assertThat(ex.steps()).containsExactly("Eins", "Zwei");
        assertThat(ex.personalized()).isFalse();
        assertThat(ex.sourceExcerpt()).isNull();
        server.verify();
    }

    @Test
    void nextWithoutCategoryLetsTheBackendRotate() {
        server.expect(requestTo("http://backend/api/gym/exercises")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(EXERCISE, MediaType.APPLICATION_JSON));
        assertThat(gym.next(null, null).category()).isEqualTo(GymCategory.REFRAMING);
        server.verify();
    }

    @Test
    void attemptPostsAnswerAndDuration() {
        server.expect(requestTo("http://backend/api/gym/exercises/4/attempts")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"answer\":\"Meine Antwort\",\"durationSeconds\":120}"))
                .andRespond(withSuccess(ATTEMPT.formatted("[]"), MediaType.APPLICATION_JSON));
        var attempt = gym.attempt(4, new AttemptRequest("Meine Antwort", 120));
        assertThat(attempt.id()).isEqualTo(9);
        assertThat(attempt.feedbackQuestions()).isEmpty();
        server.verify();
    }

    @Test
    void feedbackPostsWithoutBodyAndParsesQuestions() {
        server.expect(requestTo("http://backend/api/gym/attempts/9/feedback")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(ATTEMPT.formatted("[\"Was fällt dir auf?\"]"), MediaType.APPLICATION_JSON));
        assertThat(gym.feedback(9).feedbackQuestions()).containsExactly("Was fällt dir auf?");
        server.verify();
    }

    @Test
    void historyWithAndWithoutCategoryAndEmptyList() {
        server.expect(requestTo("http://backend/api/gym/attempts")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[" + ATTEMPT.formatted("[]") + "]", MediaType.APPLICATION_JSON));
        assertThat(gym.history(null)).hasSize(1);
        server.reset();

        server.expect(requestTo("http://backend/api/gym/attempts?category=BIAS_SPOTTING"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThat(gym.history(GymCategory.BIAS_SPOTTING)).isEmpty();
        server.verify();
    }

    @Test
    void getReadsAnExercise() {
        server.expect(requestTo("http://backend/api/gym/exercises/4")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(EXERCISE, MediaType.APPLICATION_JSON));
        assertThat(gym.get(4).title()).isEqualTo("Zwei andere Lesarten");
    }

    @Test
    void errorBodyBecomesBackendException() {
        server.expect(requestTo("http://backend/api/gym/attempts/9/feedback"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"PROVIDER_UNREACHABLE\",\"message\":\"The AI service is not available right now.\"}"));
        assertThatThrownBy(() -> gym.feedback(9)).isInstanceOfSatisfying(BackendException.class, e -> {
            assertThat(e.status()).isEqualTo(503);
            assertThat(e.error().code()).isEqualTo("PROVIDER_UNREACHABLE");
        });
    }

    @Test
    void notImplementedBackendIsAnErrorNotAnEmptyResult() {
        server.expect(requestTo("http://backend/api/gym/attempts")).andRespond(withStatus(HttpStatus.NOT_IMPLEMENTED));
        assertThatThrownBy(() -> gym.history(null)).isInstanceOfSatisfying(BackendException.class,
                e -> assertThat(e.status()).isEqualTo(501));
    }
}
