package dev.rabauer.ai.diary.rest.stub;

import dev.rabauer.ai.diary.api.coach.AnswerRequest;
import dev.rabauer.ai.diary.api.coach.ConversationDto;
import dev.rabauer.ai.diary.api.coach.StartConversationRequest;
import dev.rabauer.ai.diary.rest.CoachApi;
import dev.rabauer.ai.diary.web.ApiException;

/** Placeholder returning 501 until W2a implements {@link CoachApi}. DELETE together with the real implementation. */
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
