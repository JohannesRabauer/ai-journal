package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.gym.AttemptDto;
import dev.rabauer.ai.diary.api.gym.AttemptRequest;
import dev.rabauer.ai.diary.api.gym.ExerciseDto;
import dev.rabauer.ai.diary.api.gym.GymCategory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/** Typed client for the Thinking Gym API ({@code /api/gym}). */
@Component
public class GymClient {

    private static final ParameterizedTypeReference<List<AttemptDto>> ATTEMPTS = new ParameterizedTypeReference<>() {
    };

    private final RestClient client;
    private final ObjectMapper mapper;

    public GymClient(RestClient backendRestClient, ObjectMapper mapper) {
        this.client = backendRestClient;
        this.mapper = mapper;
    }

    /**
     * The next exercise; the backend personalizes it when an LLM is available, else it uses a built-in template.
     *
     * @param category   null lets the backend rotate through the categories
     * @param uiLanguage language tag of the UI (e.g. "de"); the backend uses it for template exercises
     */
    public ExerciseDto next(GymCategory category, String uiLanguage) {
        var request = client.post().uri(b -> {
            b.path("/api/gym/exercises");
            if (category != null) {
                b.queryParam("category", category.name());
            }
            return b.build();
        });
        if (uiLanguage != null && !uiLanguage.isBlank()) {
            request = request.header(HttpHeaders.ACCEPT_LANGUAGE, uiLanguage);
        }
        return request.retrieve().onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(ExerciseDto.class);
    }

    public ExerciseDto get(long id) {
        return client.get().uri("/api/gym/exercises/{id}", id).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(ExerciseDto.class);
    }

    public AttemptDto attempt(long exerciseId, AttemptRequest request) {
        return client.post().uri("/api/gym/exercises/{id}/attempts", exerciseId).contentType(MediaType.APPLICATION_JSON)
                .body(request).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(AttemptDto.class);
    }

    /** Optional AI follow-up questions for an attempt; only call when the user asked. */
    public AttemptDto feedback(long attemptId) {
        return client.post().uri("/api/gym/attempts/{id}/feedback", attemptId).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(AttemptDto.class);
    }

    /** Attempts, newest first; null category means all. */
    public List<AttemptDto> history(GymCategory category) {
        List<AttemptDto> list = client.get().uri(b -> {
            b.path("/api/gym/attempts");
            if (category != null) {
                b.queryParam("category", category.name());
            }
            return b.build();
        }).retrieve().onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res)).body(ATTEMPTS);
        return list == null ? List.of() : list;
    }
}
