package dev.rabauer.ai.diary.insights;

import dev.rabauer.ai.diary.api.insights.WeeklyReportDto;
import dev.rabauer.ai.diary.memory.MemoryStats;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** "What you learned" comes from MemoryStats only; absent, failing or odd statistics never break the report. */
class WeeklyLearnedTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final LocalDate FROM = LocalDate.of(2031, 3, 3);
    private static final LocalDate TO = LocalDate.of(2031, 3, 10);

    /** An Instance that is unresolvable when {@code stats} is null; only the methods the service uses are supported. */
    @SuppressWarnings("unchecked")
    private static Instance<MemoryStats> instance(MemoryStats stats) {
        return (Instance<MemoryStats>) Proxy.newProxyInstance(WeeklyLearnedTest.class.getClassLoader(), new Class<?>[]{Instance.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isResolvable" -> stats != null;
                    case "get" -> stats;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static WeeklyReportService service(MemoryStats stats) {
        WeeklyReportService s = new WeeklyReportService();
        s.memoryStats = instance(stats);
        s.clock = Clock.fixed(Instant.parse("2031-03-05T10:00:00Z"), ZONE);
        return s;
    }

    @Test
    void absentMemoryStatsMeansZero() {
        assertThat(service(null).learned(FROM, TO)).isEqualTo(new WeeklyReportDto.Learned(0, 0, List.of()));
    }

    @Test
    void presentMemoryStatsAreAskedForTheWeekInTheServerZone() {
        List<Instant[]> asked = new ArrayList<>();
        MemoryStats stats = (from, to) -> {
            asked.add(new Instant[]{from, to});
            return new MemoryStats.Learned(3, 5, List.of("What is X?", "What is Y?"));
        };
        WeeklyReportDto.Learned l = service(stats).learned(FROM, TO);
        assertThat(l).isEqualTo(new WeeklyReportDto.Learned(3, 5, List.of("What is X?", "What is Y?")));
        // Monday 00:00 Berlin (CET, UTC+1) up to the next Monday 00:00, exclusive
        assertThat(asked).singleElement().satisfies(a -> {
            assertThat(a[0]).isEqualTo(Instant.parse("2031-03-02T23:00:00Z"));
            assertThat(a[1]).isEqualTo(Instant.parse("2031-03-09T23:00:00Z"));
        });
    }

    @Test
    void failingOrOddStatisticsFallBackToZeroOrAreClamped() {
        MemoryStats failing = (from, to) -> {
            throw new IllegalStateException("boom");
        };
        assertThat(service(failing).learned(FROM, TO)).isEqualTo(new WeeklyReportDto.Learned(0, 0, List.of()));
        MemoryStats nul = (from, to) -> null;
        assertThat(service(nul).learned(FROM, TO)).isEqualTo(new WeeklyReportDto.Learned(0, 0, List.of()));
        MemoryStats odd = (from, to) -> new MemoryStats.Learned(-2, 4, List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"));
        WeeklyReportDto.Learned l = service(odd).learned(FROM, TO);
        assertThat(l.itemsCreated()).isZero();
        assertThat(l.itemPrompts()).hasSize(10);
    }
}
