package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.audio.CompleteRecordingRequest;
import dev.rabauer.ai.diary.api.audio.RecordingDto;
import dev.rabauer.ai.diary.api.audio.StartRecordingRequest;
import dev.rabauer.ai.diary.api.common.ErrorDto;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.UUID;

/**
 * Typed client for the non-streaming parts of {@code /api/recordings} (status, delete audio, start/complete).
 * Chunk upload and audio download go browser to same-origin proxy to backend and never pass through here.
 */
@Component
public class RecordingsClient {

    private final RestClient client;
    private final ObjectMapper mapper;

    public RecordingsClient(RestClient backendRestClient, ObjectMapper mapper) {
        this.client = backendRestClient;
        this.mapper = mapper;
    }

    public RecordingDto start(StartRecordingRequest request) {
        return client.post().uri("/api/recordings").contentType(MediaType.APPLICATION_JSON).body(request)
                .retrieve().onStatus(s -> s.isError(), this::fail).body(RecordingDto.class);
    }

    public RecordingDto get(UUID id) {
        return client.get().uri("/api/recordings/{id}", id)
                .retrieve().onStatus(s -> s.isError(), this::fail).body(RecordingDto.class);
    }

    public RecordingDto complete(UUID id, CompleteRecordingRequest request) {
        return client.post().uri("/api/recordings/{id}/complete", id).contentType(MediaType.APPLICATION_JSON).body(request)
                .retrieve().onStatus(s -> s.isError(), this::fail).body(RecordingDto.class);
    }

    /** Deletes the audio only; entry and transcript remain. */
    public RecordingDto deleteAudio(UUID id) {
        return client.delete().uri("/api/recordings/{id}/audio", id)
                .retrieve().onStatus(s -> s.isError(), this::fail).body(RecordingDto.class);
    }

    private void fail(org.springframework.http.HttpRequest request, org.springframework.http.client.ClientHttpResponse response) throws IOException {
        ErrorDto error;
        try {
            error = mapper.readValue(response.getBody(), ErrorDto.class);
        } catch (IOException e) {
            error = new ErrorDto("HTTP_" + response.getStatusCode().value(), "The diary service is not reachable right now.");
        }
        throw new BackendException(response.getStatusCode().value(), error);
    }
}
