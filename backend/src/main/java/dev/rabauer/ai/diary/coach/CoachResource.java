package dev.rabauer.ai.diary.coach;

import dev.rabauer.ai.diary.api.coach.AnswerRequest;
import dev.rabauer.ai.diary.api.coach.ConversationDto;
import dev.rabauer.ai.diary.api.coach.StartConversationRequest;
import dev.rabauer.ai.diary.rest.CoachApi;
import dev.rabauer.ai.diary.web.ApiException;
import jakarta.inject.Inject;

public class CoachResource implements CoachApi {

    @Inject
    CoachService service;

    @Override
    public ConversationDto start(StartConversationRequest request) {
        if (request == null) {
            throw ApiException.badRequest("INVALID_REQUEST", "entryId is required.");
        }
        return service.start(request);
    }

    @Override
    public ConversationDto get(long id) {
        return service.get(id);
    }

    @Override
    public ConversationDto answer(long id, AnswerRequest request) {
        return service.answer(id, request);
    }

    @Override
    public ConversationDto pause(long id) {
        return service.pause(id);
    }

    @Override
    public ConversationDto resume(long id) {
        return service.resume(id);
    }

    @Override
    public ConversationDto end(long id) {
        return service.end(id);
    }
}
