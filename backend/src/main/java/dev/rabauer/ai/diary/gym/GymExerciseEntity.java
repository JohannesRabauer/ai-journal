package dev.rabauer.ai.diary.gym;

import dev.rabauer.ai.diary.api.gym.GymCategory;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "gym_exercise")
public class GymExerciseEntity extends PanacheEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 32, nullable = false)
    public GymCategory category;

    /** de or en. */
    @Column(name = "language", length = 8, nullable = false)
    public String language;

    @Column(name = "title", length = 300, nullable = false)
    public String title;

    @Column(name = "prompt", columnDefinition = "TEXT", nullable = false)
    public String prompt;

    @Column(name = "source_excerpt", columnDefinition = "TEXT")
    public String sourceExcerpt;

    @Column(name = "source_entry_id")
    public Long sourceEntryId;

    /** JSON array of strings. */
    @Column(name = "steps", columnDefinition = "TEXT", nullable = false)
    public String steps;

    @Column(name = "estimated_minutes", nullable = false)
    public int estimatedMinutes;

    @Column(name = "personalized", nullable = false)
    public boolean personalized;

    /** Library id for template exercises, null when personalized. */
    @Column(name = "template_id", length = 64)
    public String templateId;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
