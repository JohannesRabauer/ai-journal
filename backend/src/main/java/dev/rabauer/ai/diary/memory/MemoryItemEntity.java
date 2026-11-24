package dev.rabauer.ai.diary.memory;

import dev.rabauer.ai.diary.api.memory.MemoryItemType;
import dev.rabauer.ai.diary.api.memory.SourceType;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** An approved memory item with its SM-2 state. Source columns are null once the source entry was deleted. */
@Entity
@Table(name = "memory_item")
public class MemoryItemEntity extends PanacheEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", length = 16, nullable = false)
    public MemoryItemType type;

    @Column(name = "prompt", columnDefinition = "TEXT", nullable = false)
    public String prompt;

    @Column(name = "answer", columnDefinition = "TEXT", nullable = false)
    public String answer;

    @Column(name = "source_quote", columnDefinition = "TEXT")
    public String sourceQuote;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 16)
    public SourceType sourceType;

    @Column(name = "source_id")
    public Long sourceId;

    @Column(name = "entry_id")
    public Long entryId;

    @Column(name = "repetitions", nullable = false)
    public int repetitions;

    @Column(name = "interval_days", nullable = false)
    public int intervalDays;

    @Column(name = "ease_factor", nullable = false)
    public double easeFactor;

    @Column(name = "next_review_on", nullable = false)
    public LocalDate nextReviewOn;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
