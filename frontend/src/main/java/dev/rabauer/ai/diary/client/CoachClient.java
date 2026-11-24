package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.coach.AnswerRequest;
import dev.rabauer.ai.diary.api.coach.ConversationDto;
import dev.rabauer.ai.diary.api.coach.StartConversationRequest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/** Typed client for the coach API ({@code /api/conversations}, {@code /api/entries/{id}/conversations}). */
@Component
public class CoachClient {

    private static final ParameterizedTypeReference<List<ConversationDto>> LIST = new ParameterizedTypeReference<>() {
    };

    private final RestClient client;
    private final ObjectMapper mapper;

    public CoachClient(RestClient backendRestClient, ObjectMapper mapper) {
        this.client = backendRestClient;
        this.mapper = mapper;
    }

    /** Conversations of an entry; the backend orders them, the view picks the latest by start time. */
    public List<ConversationDto> forEntry(long entryId) {
        List<ConversationDto> list = client.get().uri("/api/entries/{id}/conversations", entryId).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res)).body(LIST);
        return list == null ? List.of() : list;
    }

    public ConversationDto start(long entryId) {
        return client.post().uri("/api/conversations").contentType(MediaType.APPLICATION_JSON)
                .body(new StartConversationRequest(entryId)).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(ConversationDto.class);
    }

    public ConversationDto get(long id) {
        return client.get().uri("/api/conversations/{id}", id).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(ConversationDto.class);
    }

    public ConversationDto answer(long id, AnswerRequest request) {
        return client.post().uri("/api/conversations/{id}/answers", id).contentType(MediaType.APPLICATION_JSON)
                .body(request).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(ConversationDto.class);
    }

    public ConversationDto pause(long id) {
        return action(id, "pause");
    }

    public ConversationDto resume(long id) {
        return action(id, "resume");
    }

    public ConversationDto end(long id) {
        return action(id, "end");
    }

    private ConversationDto action(long id, String action) {
        return client.post().uri("/api/conversations/{id}/" + action, id).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(ConversationDto.class);
    }
}
