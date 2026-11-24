package dev.rabauer.ai.diary.memory;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/** One review cycle of an item: opened by the attempt (quality null), completed by the grade. */
@Entity
@Table(name = "memory_review")
public class MemoryReviewEntity extends PanacheEntity {

    @Column(name = "item_id", nullable = false)
    public long itemId;

    @Column(name = "attempt_text", columnDefinition = "TEXT")
    public String attemptText;

    @Column(name = "attempted_at", nullable = false)
    public Instant attemptedAt;

    @Column(name = "quality")
    public Integer quality;

    @Column(name = "graded_at")
    public Instant gradedAt;

    @Column(name = "interval_days")
    public Integer intervalDays;
}
