package dev.rabauer.ai.diary.memory;

import dev.rabauer.ai.diary.api.memory.MemoryCandidateDto;
import dev.rabauer.ai.diary.privacy.ExportSink;
import dev.rabauer.ai.diary.privacy.UserDataOwner;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Export ({@code memory/candidates.json}, {@code memory/items.json}, {@code memory/reviews.json}), delete-all and
 * the {@link MemoryStats} implementation. Stats expose counts and item prompts only, never answers or quotes.
 */
@ApplicationScoped
public class MemoryDataOwner implements UserDataOwner, MemoryStats {

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public int deleteOrder() {
        return 400;
    }

    @Override
    public long deleteAll() {
        return QuarkusTransaction.requiringNew().call(() ->
                MemoryReviewEntity.deleteAll() + MemoryItemEntity.deleteAll() + MemoryCandidateEntity.deleteAll());
    }

    @Override
    public void export(ExportSink sink) throws IOException {
        List<MemoryCandidateDto> candidates = QuarkusTransaction.requiringNew().call(() ->
                MemoryCandidateEntity.<MemoryCandidateEntity>list("order by id").stream().map(MemoryService::toDto).toList());
        var items = QuarkusTransaction.requiringNew().call(() ->
                MemoryItemEntity.<MemoryItemEntity>list("order by id").stream().map(MemoryService::toDto).toList());
        var reviews = QuarkusTransaction.requiringNew().call(() ->
                MemoryReviewEntity.<MemoryReviewEntity>list("order by id").stream()
                        .map(r -> new ExportedReview(r.id, r.itemId, r.attemptText, r.attemptedAt, r.quality, r.gradedAt, r.intervalDays))
                        .toList());
        sink.json("memory/candidates.json", candidates);
        sink.json("memory/items.json", items);
        sink.json("memory/reviews.json", reviews);
    }

    @Override
    public Learned learnedBetween(Instant from, Instant to) {
        return QuarkusTransaction.requiringNew().call(() -> {
            long created = MemoryItemEntity.count("createdAt >= ?1 and createdAt < ?2", from, to);
            long reviews = MemoryReviewEntity.count("gradedAt >= ?1 and gradedAt < ?2", from, to);
            List<String> prompts = MemoryItemEntity.<MemoryItemEntity>find(
                            "createdAt >= ?1 and createdAt < ?2 order by createdAt desc, id desc", from, to)
                    .page(0, 10).list().stream().map(i -> i.prompt).toList();
            return new Learned((int) created, (int) reviews, prompts);
        });
    }

    record ExportedReview(long id, long itemId, String attemptText, Instant attemptedAt, Integer quality, Instant gradedAt,
                          Integer intervalDays) {
    }
}
