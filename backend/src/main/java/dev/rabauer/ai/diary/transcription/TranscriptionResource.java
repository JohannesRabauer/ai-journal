package dev.rabauer.ai.diary.transcription;

import dev.rabauer.ai.diary.api.transcription.TranscriptionJobDto;
import dev.rabauer.ai.diary.rest.TranscriptionsApi;
import jakarta.inject.Inject;

public class TranscriptionResource implements TranscriptionsApi {

    @Inject
    TranscriptionService service;

    @Override
    public TranscriptionJobDto get(long id) {
        return service.get(id);
    }
}
