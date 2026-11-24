package dev.rabauer.ai.diary.insights;

import dev.rabauer.ai.diary.api.common.Language;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/** One report per (iso_year, iso_week). The six sections are stored as one JSON document ({@link WeeklyContent}). */
@Entity
@Table(name = "weekly_report")
public class WeeklyReportEntity extends PanacheEntity {

    @Column(name = "iso_year", nullable = false)
    public int isoYear;

    @Column(name = "iso_week", nullable = false)
    public int isoWeek;

    @Enumerated(EnumType.STRING)
    @Column(name = "language", length = 8, nullable = false)
    public Language language;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    public String content;

    @Column(name = "generated_at", nullable = false)
    public Instant generatedAt;

    @Column(name = "edited_at")
    public Instant editedAt;
}
