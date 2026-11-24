package dev.rabauer.ai.diary.memory;

import dev.rabauer.ai.diary.api.memory.CandidateStatus;
import dev.rabauer.ai.diary.api.memory.MemoryItemType;
import dev.rabauer.ai.diary.api.memory.SourceType;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/** A proposed item from the LLM. Nothing becomes a memory item until the user approves it. */
@Entity
@Table(name = "memory_candidate")
public class MemoryCandidateEntity extends PanacheEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 16, nullable = false)
    public SourceType sourceType;

    @Column(name = "source_id", nullable = false)
    public long sourceId;

    /** The entry the source belongs to (for a conversation: its entry), so deleting the entry finds this row. */
    @Column(name = "entry_id", nullable = false)
    public long entryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", length = 16, nullable = false)
    public MemoryItemType type;

    @Column(name = "prompt", columnDefinition = "TEXT", nullable = false)
    public String prompt;

    @Column(name = "answer", columnDefinition = "TEXT", nullable = false)
    public String answer;

    @Column(name = "source_quote", columnDefinition = "TEXT", nullable = false)
    public String sourceQuote;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    public CandidateStatus status;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
