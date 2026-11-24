package dev.rabauer.ai.diary.insights;

import dev.rabauer.ai.diary.privacy.ExportSink;
import dev.rabauer.ai.diary.privacy.UserDataOwner;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.List;

/** Export ({@code weekly/reports.json}) and delete-all for weekly reflections. */
@ApplicationScoped
public class WeeklyDataOwner implements UserDataOwner {

    @Inject
    WeeklyReportRepository reports;
    @Inject
    WeeklyReportService service;

    @Override
    public String name() {
        return "weekly";
    }

    @Override
    public int deleteOrder() {
        return 400;
    }

    @Override
    public long deleteAll() {
        return QuarkusTransaction.requiringNew().call(() -> reports.deleteAll());
    }

    @Override
    public void export(ExportSink sink) throws IOException {
        List<?> all = QuarkusTransaction.requiringNew().call(() -> reports.newestWeekFirst().stream().map(service::toDto).toList());
        sink.json("weekly/reports.json", all);
    }
}
