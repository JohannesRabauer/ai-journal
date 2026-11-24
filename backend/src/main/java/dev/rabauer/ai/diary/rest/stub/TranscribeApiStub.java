package dev.rabauer.ai.diary.rest.stub;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.transcription.TranscribeResultDto;
import dev.rabauer.ai.diary.rest.TranscribeApi;
import dev.rabauer.ai.diary.web.ApiException;

import java.io.InputStream;

/** Placeholder returning 501 until W2a implements {@link TranscribeApi}. DELETE together with the real implementation. */
public class TranscribeApiStub implements TranscribeApi {

    @Override
    public TranscribeResultDto transcribe(Language language, String contentType, InputStream audio) {
        throw ApiException.notImplemented("Voice answers");
    }
}
