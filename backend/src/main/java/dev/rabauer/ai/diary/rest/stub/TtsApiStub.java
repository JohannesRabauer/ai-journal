package dev.rabauer.ai.diary.rest.stub;

import dev.rabauer.ai.diary.api.tts.SpeakRequest;
import dev.rabauer.ai.diary.api.tts.VoiceDto;
import jakarta.ws.rs.core.Response;

import java.util.List;
import dev.rabauer.ai.diary.rest.TtsApi;
import dev.rabauer.ai.diary.web.ApiException;

/**
 * Placeholder returning 501 until the owning worker implements {@link TtsApi}. DELETE this class in the same
 * commit that adds the real implementation.
 */
public class TtsApiStub implements TtsApi {
    @Override
    public Response speak(SpeakRequest request) {
        return Response.noContent().build();
    }

    @Override
    public List<VoiceDto> voices() {
        return List.of();
    }
}
