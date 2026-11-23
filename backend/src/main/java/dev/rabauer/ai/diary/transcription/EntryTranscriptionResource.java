package dev.rabauer.ai.diary.transcription;

import dev.rabauer.ai.diary.api.transcription.StartTranscriptionRequest;
import dev.rabauer.ai.diary.api.transcription.TranscriptionJobDto;
import dev.rabauer.ai.diary.rest.EntryTranscriptionsApi;
import jakarta.inject.Inject;

public class EntryTranscriptionResource implements EntryTranscriptionsApi {

    @Inject
    TranscriptionService service;

    @Override
    public TranscriptionJobDto start(long entryId, StartTranscriptionRequest request) {
        return service.start(entryId, request);
    }

    @Override
    public TranscriptionJobDto latest(long entryId) {
        return service.latest(entryId);
    }
}
