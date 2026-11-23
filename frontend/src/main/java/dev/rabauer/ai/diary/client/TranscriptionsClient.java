package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.common.ErrorDto;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.transcription.StartTranscriptionRequest;
import dev.rabauer.ai.diary.api.transcription.TranscriptionJobDto;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.Optional;

/** Typed client for transcription jobs (backend TranscriptionsApi). */
@Component
public class TranscriptionsClient {

    private final RestClient client;
    private final ObjectMapper mapper;

    public TranscriptionsClient(RestClient backendRestClient, ObjectMapper mapper) {
        this.client = backendRestClient;
        this.mapper = mapper;
    }

    /** Starts or retries transcription (async). 409 if there is no complete recording or a job is running. */
    public TranscriptionJobDto start(long entryId, Language language) {
        return client.post().uri("/api/entries/{id}/transcriptions", entryId).contentType(MediaType.APPLICATION_JSON)
                .body(new StartTranscriptionRequest(language == null ? Language.AUTO : language))
                .retrieve().onStatus(s -> s.isError(), this::fail).body(TranscriptionJobDto.class);
    }

    /** Latest job of the entry; empty while no job exists yet (404). */
    public Optional<TranscriptionJobDto> latest(long entryId) {
        try {
            return Optional.ofNullable(client.get().uri("/api/entries/{id}/transcriptions/latest", entryId)
                    .retrieve().onStatus(s -> s.isError(), this::fail).body(TranscriptionJobDto.class));
        } catch (BackendException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
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
