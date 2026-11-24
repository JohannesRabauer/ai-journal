package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.tts.SpeakRequest;
import dev.rabauer.ai.diary.api.tts.VoiceDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

/**
 * Typed client for {@code /api/tts}. The browser fetches audio through the same-origin proxy; this client serves
 * server-side callers (voice list, tests).
 */
@Component
public class TtsClient {

    private static final ParameterizedTypeReference<List<VoiceDto>> VOICES = new ParameterizedTypeReference<>() {
    };

    private final RestClient client;
    private final ObjectMapper mapper;

    public TtsClient(RestClient backendRestClient, ObjectMapper mapper) {
        this.client = backendRestClient;
        this.mapper = mapper;
    }

    public List<VoiceDto> voices() {
        List<VoiceDto> voices = client.get().uri("/api/tts/voices").retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res)).body(VOICES);
        return voices == null ? List.of() : voices;
    }

    /** Audio bytes, or empty when the backend answers 204 (TTS disabled or failing: show text only). */
    public Optional<byte[]> speak(SpeakRequest request) {
        return client.post().uri("/api/tts").contentType(MediaType.APPLICATION_JSON).body(request)
                .exchange((req, res) -> {
                    if (res.getStatusCode().value() == 204) {
                        return Optional.<byte[]>empty();
                    }
                    if (res.getStatusCode().isError()) {
                        throw BackendErrors.from(mapper, res);
                    }
                    byte[] body = res.getBody().readAllBytes();
                    return body.length == 0 ? Optional.<byte[]>empty() : Optional.of(body);
                });
    }
}
