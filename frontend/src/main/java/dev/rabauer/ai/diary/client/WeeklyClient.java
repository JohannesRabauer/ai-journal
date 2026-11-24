package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.insights.GenerateWeeklyReportRequest;
import dev.rabauer.ai.diary.api.insights.WeeklyReportDto;
import dev.rabauer.ai.diary.api.insights.WeeklyReportSummaryDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/** Typed client for {@code /api/weekly-reports} (backend WeeklyReportsApi). */
@Component
public class WeeklyClient {

    private static final ParameterizedTypeReference<List<WeeklyReportSummaryDto>> LIST = new ParameterizedTypeReference<>() {
    };

    private final RestClient client;
    private final RestClient generation;
    private final ObjectMapper mapper;

    /**
     * Generation runs the model up to three times, far beyond the 30 s read timeout of the other calls, so it gets
     * its own client with a longer one ({@code weekly.generate-timeout}, default 5 minutes).
     */
    @Autowired
    public WeeklyClient(RestClient backendRestClient, ObjectMapper mapper,
                        @Value("${weekly.generate-timeout:PT5M}") Duration generateTimeout) {
        this(backendRestClient, longReadTimeout(backendRestClient, generateTimeout), mapper);
    }

    WeeklyClient(RestClient client, RestClient generation, ObjectMapper mapper) {
        this.client = client;
        this.generation = generation;
        this.mapper = mapper;
    }

    private static RestClient longReadTimeout(RestClient base, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout((int) readTimeout.toMillis());
        return base.mutate().requestFactory(factory).build();
    }

    /** Generates the report of an ISO week, or replaces the stored one (the model call can take a while). */
    public WeeklyReportDto generate(int isoYear, int isoWeek) {
        return generation.post().uri("/api/weekly-reports").contentType(MediaType.APPLICATION_JSON)
                .body(new GenerateWeeklyReportRequest(isoYear, isoWeek)).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(WeeklyReportDto.class);
    }

    /** Newest week first. */
    public List<WeeklyReportSummaryDto> list() {
        List<WeeklyReportSummaryDto> list = client.get().uri("/api/weekly-reports").retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res)).body(LIST);
        return list == null ? List.of() : list;
    }

    public WeeklyReportDto get(long id) {
        return client.get().uri("/api/weekly-reports/{id}", id).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(WeeklyReportDto.class);
    }

    public WeeklyReportDto update(long id, WeeklyReportDto report) {
        return client.put().uri("/api/weekly-reports/{id}", id).contentType(MediaType.APPLICATION_JSON).body(report).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(WeeklyReportDto.class);
    }

    public void delete(long id) {
        client.delete().uri("/api/weekly-reports/{id}", id).retrieve()
                .onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res)).toBodilessEntity();
    }
}
