package dev.rabauer.ai.diary.insights;

import dev.rabauer.ai.diary.api.insights.GenerateWeeklyReportRequest;
import dev.rabauer.ai.diary.api.insights.WeeklyReportDto;
import dev.rabauer.ai.diary.api.insights.WeeklyReportSummaryDto;
import dev.rabauer.ai.diary.rest.WeeklyReportsApi;
import jakarta.inject.Inject;

import java.util.List;

public class WeeklyReportsResource implements WeeklyReportsApi {

    @Inject
    WeeklyReportService service;

    @Override
    public WeeklyReportDto generate(GenerateWeeklyReportRequest request) {
        return service.generate(request);
    }

    @Override
    public List<WeeklyReportSummaryDto> list() {
        return service.list();
    }

    @Override
    public WeeklyReportDto get(long id) {
        return service.get(id);
    }

    @Override
    public WeeklyReportDto update(long id, WeeklyReportDto report) {
        return service.update(id, report);
    }

    @Override
    public void delete(long id) {
        service.delete(id);
    }
}
