package dev.rabauer.ai.diary.insights;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class WeeklyReportRepository implements PanacheRepository<WeeklyReportEntity> {

    /** Newest week first. */
    public List<WeeklyReportEntity> newestWeekFirst() {
        return listAll(Sort.by("isoYear", Sort.Direction.Descending).and("isoWeek", Sort.Direction.Descending));
    }

    public WeeklyReportEntity forWeek(int isoYear, int isoWeek) {
        return find("isoYear = ?1 and isoWeek = ?2", isoYear, isoWeek).firstResult();
    }
}
