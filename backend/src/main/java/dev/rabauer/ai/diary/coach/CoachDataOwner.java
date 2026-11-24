package dev.rabauer.ai.diary.coach;

import dev.rabauer.ai.diary.api.coach.ConversationDto;
import dev.rabauer.ai.diary.entry.EntryConversationIndicator;
import dev.rabauer.ai.diary.privacy.ExportSink;
import dev.rabauer.ai.diary.privacy.UserDataOwner;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;

/** Export ({@code coach/conversations.json}), delete-all and the entry-list badge for coach conversations. */
@ApplicationScoped
public class CoachDataOwner implements UserDataOwner, EntryConversationIndicator {

    @Inject
    CoachConversationRepository conversations;
    @Inject
    CoachMessageRepository messages;

    @Override
    public String name() {
        return "coach";
    }

    @Override
    public int deleteOrder() {
        return 300;
    }

    @Override
    public long deleteAll() {
        return QuarkusTransaction.requiringNew().call(() -> messages.deleteAll() + conversations.deleteAll());
    }

    /** Exports all conversations; planned-but-never-shown opening questions are part of the data and included. */
    @Override
    public void export(ExportSink sink) throws IOException {
        List<ExportedConversation> out = QuarkusTransaction.requiringNew().call(() -> conversations.listAll().stream()
                .sorted(Comparator.comparingLong(c -> c.id))
                .map(c -> {
                    List<CoachMessageEntity> all = messages.forConversation(c.id);
                    ConversationDto dto = CoachService.toDto(c, all);
                    List<CoachMessageEntity> planned = all.stream().filter(m -> !m.revealed()).toList();
                    return new ExportedConversation(dto, planned.stream().map(m -> m.content).toList());
                }).toList());
        sink.json("coach/conversations.json", out);
    }

    @Override
    public boolean hasConversation(long entryId) {
        return QuarkusTransaction.joiningExisting().call(() -> conversations.count("entryId", entryId) > 0);
    }

    /** @param unrevealedOpeningQuestions planned opening questions the user never saw */
    record ExportedConversation(ConversationDto conversation, List<String> unrevealedOpeningQuestions) {
    }
}
