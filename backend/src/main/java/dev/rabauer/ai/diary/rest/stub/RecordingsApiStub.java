package dev.rabauer.ai.diary.rest.stub;

import dev.rabauer.ai.diary.api.audio.ChunkAckDto;
import dev.rabauer.ai.diary.api.audio.CompleteRecordingRequest;
import dev.rabauer.ai.diary.api.audio.RecordingDto;
import dev.rabauer.ai.diary.api.audio.StartRecordingRequest;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.util.UUID;
import dev.rabauer.ai.diary.rest.RecordingsApi;
import dev.rabauer.ai.diary.web.ApiException;

/**
 * Placeholder returning 501 until the owning worker implements {@link RecordingsApi}. DELETE this class in the same
 * commit that adds the real implementation.
 */
public class RecordingsApiStub implements RecordingsApi {
    @Override
    public RecordingDto start(StartRecordingRequest request) {
        throw ApiException.notImplemented("Recording");
    }

    @Override
    public ChunkAckDto uploadChunk(UUID id, int index, InputStream body) {
        throw ApiException.notImplemented("Recording");
    }

    @Override
    public RecordingDto get(UUID id) {
        throw ApiException.notImplemented("Recording");
    }

    @Override
    public RecordingDto complete(UUID id, CompleteRecordingRequest request) {
        throw ApiException.notImplemented("Recording");
    }

    @Override
    public Response audio(UUID id) {
        throw ApiException.notImplemented("Recording");
    }

    @Override
    public RecordingDto deleteAudio(UUID id) {
        throw ApiException.notImplemented("Recording");
    }
}
