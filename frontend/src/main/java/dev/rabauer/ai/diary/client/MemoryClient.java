package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.memory.CandidateDecisionRequest;
import dev.rabauer.ai.diary.api.memory.CandidateStatus;
import dev.rabauer.ai.diary.api.memory.ExtractionRequest;
import dev.rabauer.ai.diary.api.memory.LessonDto;
import dev.rabauer.ai.diary.api.memory.MemoryCandidateDto;
import dev.rabauer.ai.diary.api.memory.MemoryItemDto;
import dev.rabauer.ai.diary.api.memory.MemoryItemType;
import dev.rabauer.ai.diary.api.memory.RevealDto;
import dev.rabauer.ai.diary.api.memory.ReviewAttemptRequest;
import dev.rabauer.ai.diary.api.memory.ReviewCardDto;
import dev.rabauer.ai.diary.api.memory.ReviewGradeRequest;
import dev.rabauer.ai.diary.api.memory.SourceType;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Locale;

/** Typed client for the memory API ({@code /api/memory/...}). Errors become {@link BackendException}. */
@Component
public class MemoryClient {

    private static final ParameterizedTypeReference<List<MemoryCandidateDto>> CANDIDATES = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<MemoryItemDto>> ITEMS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<ReviewCardDto>> CARDS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<LessonDto>> LESSONS = new ParameterizedTypeReference<>() {
    };

    private final RestClient client;
    private final ObjectMapper mapper;

    public MemoryClient(RestClient backendRestClient, ObjectMapper mapper) {
        this.client = backendRestClient;
        this.mapper = mapper;
    }

    /** Asks the LLM for candidates; slow, call off the UI thread. Returns only the newly stored candidates. */
    public List<MemoryCandidateDto> extract(SourceType type, long sourceId) {
        return orEmpty(client.post().uri("/api/memory/extractions").contentType(MediaType.APPLICATION_JSON)
                .body(new ExtractionRequest(type, sourceId)).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res)).body(CANDIDATES));
    }

    public List<MemoryCandidateDto> candidates(CandidateStatus status) {
        return orEmpty(client.get().uri("/api/memory/candidates?status={s}", status.name()).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res)).body(CANDIDATES));
    }

    public MemoryCandidateDto approve(long id, String prompt, String answer, MemoryItemType type) {
        return decide(id, new CandidateDecisionRequest(CandidateDecisionRequest.Decision.APPROVE, prompt, answer, type));
    }

    public MemoryCandidateDto discard(long id) {
        return decide(id, new CandidateDecisionRequest(CandidateDecisionRequest.Decision.DISCARD, null, null, null));
    }

    private MemoryCandidateDto decide(long id, CandidateDecisionRequest request) {
        return client.post().uri("/api/memory/candidates/{id}/decision", id).contentType(MediaType.APPLICATION_JSON)
                .body(request).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(MemoryCandidateDto.class);
    }

    public List<MemoryItemDto> items() {
        return orEmpty(client.get().uri("/api/memory/items").retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res)).body(ITEMS));
    }

    public void deleteItem(long id) {
        client.delete().uri("/api/memory/items/{id}", id).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res)).toBodilessEntity();
    }

    /** Cards due today: prompts only. */
    public List<ReviewCardDto> due() {
        return orEmpty(client.get().uri("/api/memory/reviews/due").retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res)).body(CARDS));
    }

    /** @param attemptText the typed answer, or null/blank for "I answered in my head" */
    public RevealDto attempt(long itemId, String attemptText) {
        return client.post().uri("/api/memory/reviews/{id}/attempt", itemId).contentType(MediaType.APPLICATION_JSON)
                .body(new ReviewAttemptRequest(attemptText)).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(RevealDto.class);
    }

    public MemoryItemDto grade(long itemId, int quality) {
        return client.post().uri("/api/memory/reviews/{id}/grade", itemId).contentType(MediaType.APPLICATION_JSON)
                .body(new ReviewGradeRequest(quality)).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(MemoryItemDto.class);
    }

    /** Lessons in the UI language (sent as Accept-Language). */
    public List<LessonDto> lessons(Locale ui) {
        String lang = ui != null && "de".equals(ui.getLanguage()) ? "de" : "en";
        return orEmpty(client.get().uri("/api/memory/lessons").header(HttpHeaders.ACCEPT_LANGUAGE, lang).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res)).body(LESSONS));
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
