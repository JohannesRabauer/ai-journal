package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.transcription.TranscribeResultDto;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Typed client for {@code POST /api/transcribe} (D-025): short audio in, text out, nothing persisted. */
@Component
public class TranscribeClient {

    private final RestClient client;
    private final ObjectMapper mapper;

    public TranscribeClient(RestClient backendRestClient, ObjectMapper mapper) {
        this.client = backendRestClient;
        this.mapper = mapper;
    }

    public TranscribeResultDto transcribe(byte[] audio, String contentType, Language language) {
        Language lang = language == null ? Language.AUTO : language;
        return client.post().uri(b -> b.path("/api/transcribe").queryParam("language", lang.name()).build())
                .contentType(MediaType.parseMediaType(contentType == null ? "application/octet-stream" : contentType))
                .body(audio).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(TranscribeResultDto.class);
    }
}
