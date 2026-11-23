package dev.rabauer.ai.diary.entry;

import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * A journal entry. Table {@code documents} is the pre-v1 table (kept for data continuity); columns from V2 on
 * are additive. Column names are explicit because the legacy columns have no underscores.
 */
@Entity
@Table(name = "documents")
public class DiaryEntryEntity extends PanacheEntity {

    @Column(name = "entrytext", columnDefinition = "TEXT")
    public String entryText;

    /** When the entry was written/spoken (local time). Legacy column name. */
    @Column(name = "timestamp")
    public LocalDateTime timestamp;

    /** Pre-v1 automatic AI remark. Read-only; never written by v1 code. */
    @Column(name = "aicontent", columnDefinition = "TEXT", updatable = false, insertable = false)
    public String aiContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    public EntryStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16, nullable = false)
    public EntrySource source;

    /** de, en or null (unknown). */
    @Column(name = "language", length = 8)
    public String language;

    @Column(name = "created_at")
    public Instant createdAt;

    @Column(name = "updated_at")
    public Instant updatedAt;

    @Column(name = "finished_at")
    public Instant finishedAt;
}
