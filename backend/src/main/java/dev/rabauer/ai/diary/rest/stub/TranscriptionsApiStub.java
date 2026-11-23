package dev.rabauer.ai.diary.rest.stub;

import dev.rabauer.ai.diary.api.transcription.StartTranscriptionRequest;
import dev.rabauer.ai.diary.api.transcription.TranscriptionJobDto;
import dev.rabauer.ai.diary.rest.TranscriptionsApi;
import dev.rabauer.ai.diary.web.ApiException;

/**
 * Placeholder returning 501 until the owning worker implements {@link TranscriptionsApi}. DELETE this class in the same
 * commit that adds the real implementation.
 */
public class TranscriptionsApiStub implements TranscriptionsApi {
    @Override
    public TranscriptionJobDto start(long entryId, StartTranscriptionRequest request) {
        throw ApiException.notImplemented("Transcription");
    }

    @Override
    public TranscriptionJobDto latest(long entryId) {
        throw ApiException.notImplemented("Transcription");
    }

    @Override
    public TranscriptionJobDto get(long id) {
        throw ApiException.notImplemented("Transcription");
    }
}
