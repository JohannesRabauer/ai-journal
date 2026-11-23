package dev.rabauer.ai.diary.rest.stub;

import dev.rabauer.ai.diary.api.coach.AnswerRequest;
import dev.rabauer.ai.diary.api.coach.ConversationDto;
import dev.rabauer.ai.diary.api.coach.StartConversationRequest;

import java.util.List;
import dev.rabauer.ai.diary.rest.CoachApi;
import dev.rabauer.ai.diary.web.ApiException;

/**
 * Placeholder returning 501 until the owning worker implements {@link CoachApi}. DELETE this class in the same
 * commit that adds the real implementation.
 */
public class CoachApiStub implements CoachApi {
    @Override
    public ConversationDto start(StartConversationRequest request) {
        throw ApiException.notImplemented("Coach");
    }

    @Override
    public ConversationDto get(long id) {
        throw ApiException.notImplemented("Coach");
    }

    @Override
    public List<ConversationDto> forEntry(long entryId) {
        throw ApiException.notImplemented("Coach");
    }

    @Override
    public ConversationDto answer(long id, AnswerRequest request) {
        throw ApiException.notImplemented("Coach");
    }

    @Override
    public ConversationDto pause(long id) {
        throw ApiException.notImplemented("Coach");
    }

    @Override
    public ConversationDto resume(long id) {
        throw ApiException.notImplemented("Coach");
    }

    @Override
    public ConversationDto end(long id) {
        throw ApiException.notImplemented("Coach");
    }
}
