package dev.rabauer.ai.diary.coach;

import dev.rabauer.ai.diary.api.coach.ConversationDto;
import dev.rabauer.ai.diary.rest.EntryConversationsApi;
import jakarta.inject.Inject;

import java.util.List;

public class EntryConversationsResource implements EntryConversationsApi {

    @Inject
    CoachService service;

    @Override
    public List<ConversationDto> forEntry(long entryId) {
        return service.forEntry(entryId);
    }
}
