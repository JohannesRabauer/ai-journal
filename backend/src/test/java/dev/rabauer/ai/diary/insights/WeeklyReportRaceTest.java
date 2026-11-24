package dev.rabauer.ai.diary.insights;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.api.insights.WeeklyReportDto;
import dev.rabauer.ai.diary.entry.DiaryEntryEntity;
import dev.rabauer.ai.diary.entry.EntryRepository;
import dev.rabauer.ai.diary.privacy.PrivacyService;
import dev.rabauer.ai.diary.privacy.PurgeGeneration;
import dev.rabauer.ai.diary.web.ApiException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** M1: a weekly report whose entries were deleted (or purged) while the model was working is not stored as is. */
@QuarkusTest
class WeeklyReportRaceTest {

    private static final int YEAR = 2023;
    private static final int WEEK = 20;

    @Inject
    WeeklyReportService service;
    @Inject
    WeeklyDataOwner owner;
    @Inject
    WeeklyReportRepository reports;
    @Inject
    EntryRepository entries;
    @Inject
    PurgeGeneration purge;
    @Inject
    PrivacyService privacy;

    @BeforeEach
    void clean() {
        owner.deleteAll();
    }

    private long entry() {
        return QuarkusTransaction.requiringNew().call(() -> {
            DiaryEntryEntity e = new DiaryEntryEntity();
            e.entryText = "text";
            e.timestamp = LocalDateTime.of(2023, 5, 16, 10, 0);
            e.status = EntryStatus.FINISHED;
            e.source = EntrySource.TEXT;
            e.createdAt = e.updatedAt = Instant.now();
            e.finishedAt = Instant.now();
            entries.persist(e);
            return e.id;
        });
    }

    private static WeeklyContent content(long a, long b) {
        return new WeeklyContent(
                List.of(new WeeklyReportDto.Theme("T", "D", List.of(a, b))),
                List.of(new WeeklyReportDto.Quote("q1", a), new WeeklyReportDto.Quote("q2", b)),
                List.of(), new WeeklyReportDto.Learned(0, 0, List.of()), List.of(), "Q?");
    }

    private long count() {
        return QuarkusTransaction.requiringNew().call(() -> reports.count());
    }

    @Test
    void deletedEntriesAreDroppedFromThemesAndQuotes() {
        long a = entry();
        long b = entry();
        QuarkusTransaction.requiringNew().run(() -> entries.deleteById(b));
        WeeklyReportDto stored = QuarkusTransaction.requiringNew().call(
                () -> service.store(YEAR, WEEK, Language.EN, content(a, b), purge.current()));
        assertThat(stored.recurringThemes().get(0).entryIds()).containsExactly(a);
        assertThat(stored.inYourOwnWords()).extracting(WeeklyReportDto.Quote::entryId).containsExactly(a);
        QuarkusTransaction.requiringNew().run(() -> entries.deleteById(a));
    }

    @Test
    void nothingIsStoredWhenAllReferencedEntriesAreGone() {
        long a = entry();
        long b = entry();
        QuarkusTransaction.requiringNew().run(() -> {
            entries.deleteById(a);
            entries.deleteById(b);
        });
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().call(
                () -> service.store(YEAR, WEEK, Language.EN, content(a, b), purge.current())))
                .isInstanceOf(ApiException.class);
        assertThat(count()).isZero();
    }

    @Test
    void nothingIsStoredAfterDeleteAllStartedMeanwhile() {
        long a = entry();
        long captured = purge.current();
        privacy.deleteAll();
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().call(
                () -> service.store(YEAR, WEEK, Language.EN, content(a, a), captured)))
                .isInstanceOf(ApiException.class);
        assertThat(count()).isZero();
    }
}
