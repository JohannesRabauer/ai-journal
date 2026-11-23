package dev.rabauer.ai.diary.audio;

import dev.rabauer.ai.diary.api.audio.ChunkAckDto;
import dev.rabauer.ai.diary.api.audio.CompleteRecordingRequest;
import dev.rabauer.ai.diary.api.audio.RecordingDto;
import dev.rabauer.ai.diary.api.audio.StartRecordingRequest;
import dev.rabauer.ai.diary.rest.RecordingsApi;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.util.UUID;

public class RecordingResource implements RecordingsApi {

    @Inject
    RecordingService service;

    @Override
    public RecordingDto start(StartRecordingRequest request) {
        return service.start(request);
    }

    @Override
    public ChunkAckDto uploadChunk(UUID id, int index, InputStream body) {
        return service.uploadChunk(id, index, body);
    }

    @Override
    public RecordingDto get(UUID id) {
        return service.get(id);
    }

    @Override
    public RecordingDto complete(UUID id, CompleteRecordingRequest request) {
        return service.complete(id, request);
    }

    @Override
    public Response audio(UUID id) {
        return service.audio(id);
    }

    @Override
    public RecordingDto deleteAudio(UUID id) {
        return service.deleteAudio(id);
    }
}
