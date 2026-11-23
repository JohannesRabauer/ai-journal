package dev.rabauer.ai.diary.rest.stub;

import dev.rabauer.ai.diary.api.coach.ConversationDto;
import dev.rabauer.ai.diary.rest.EntryConversationsApi;

import java.util.List;

/** Until W2a lands an entry has no conversations. DELETE together with the real implementation. */
public class EntryConversationsApiStub implements EntryConversationsApi {

    @Override
    public List<ConversationDto> forEntry(long entryId) {
        return List.of();
    }
}
