package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.insights.WeeklyReportDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WeeklyClientTest {

    private static final String REPORT = "{\"id\":5,\"isoYear\":2031,\"isoWeek\":10,\"language\":\"EN\","
            + "\"recurringThemes\":[{\"title\":\"Budget\",\"description\":\"d\",\"entryIds\":[1,2]}],"
            + "\"inYourOwnWords\":[{\"text\":\"I felt unsure\",\"entryId\":1}],\"openThreads\":[\"t\"],"
            + "\"whatYouLearned\":{\"itemsCreated\":3,\"reviewsDone\":5,\"itemPrompts\":[\"q?\"]},"
            + "\"noticedPatterns\":[\"p\"],\"questionForNextWeek\":\"Next?\","
            + "\"generatedAt\":\"2031-03-10T08:00:00Z\",\"editedAt\":null}";

    private MockRestServiceServer server;
    private WeeklyClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend");
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient rest = builder.build();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        client = new WeeklyClient(rest, rest, mapper);
    }

    @Test
    void generatePostsYearAndWeekAndParsesAllSections() {
        server.expect(requestTo("http://backend/api/weekly-reports")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"isoYear\":2031,\"isoWeek\":10}"))
                .andRespond(withSuccess(REPORT, MediaType.APPLICATION_JSON));

        WeeklyReportDto r = client.generate(2031, 10);

        assertThat(r.id()).isEqualTo(5);
        assertThat(r.language()).isEqualTo(Language.EN);
        assertThat(r.recurringThemes().get(0).entryIds()).containsExactly(1L, 2L);
        assertThat(r.inYourOwnWords().get(0).entryId()).isEqualTo(1L);
        assertThat(r.whatYouLearned().itemsCreated()).isEqualTo(3);
        assertThat(r.whatYouLearned().itemPrompts()).containsExactly("q?");
        assertThat(r.questionForNextWeek()).isEqualTo("Next?");
        assertThat(r.generatedAt()).isEqualTo(Instant.parse("2031-03-10T08:00:00Z"));
        assertThat(r.editedAt()).isNull();
        server.verify();
    }

    @Test
    void listParsesSummariesAndEmptyList() {
        server.expect(requestTo("http://backend/api/weekly-reports")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":2,\"isoYear\":2031,\"isoWeek\":10,\"generatedAt\":\"2031-03-10T08:00:00Z\"},"
                        + "{\"id\":1,\"isoYear\":2031,\"isoWeek\":9,\"generatedAt\":\"2031-03-03T08:00:00Z\"}]", MediaType.APPLICATION_JSON));
        var list = client.list();
        assertThat(list).extracting(s -> s.isoWeek()).containsExactly(10, 9);
        server.reset();

        server.expect(requestTo("http://backend/api/weekly-reports")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThat(client.list()).isEmpty();
        server.verify();
    }

    @Test
    void getUpdateAndDeleteUseTheReportId() {
        server.expect(requestTo("http://backend/api/weekly-reports/5")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(REPORT, MediaType.APPLICATION_JSON));
        WeeklyReportDto r = client.get(5);
        server.verify();
        server.reset();

        server.expect(requestTo("http://backend/api/weekly-reports/5")).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"id\":5,\"questionForNextWeek\":\"Mine?\",\"openThreads\":[\"t\"]}"))
                .andRespond(withSuccess(REPORT.replace("Next?", "Mine?"), MediaType.APPLICATION_JSON));
        WeeklyReportDto edited = new WeeklyReportDto(r.id(), r.isoYear(), r.isoWeek(), r.language(), r.recurringThemes(),
                r.inYourOwnWords(), r.openThreads(), r.whatYouLearned(), r.noticedPatterns(), "Mine?",
                r.generatedAt().truncatedTo(ChronoUnit.SECONDS), null);
        assertThat(client.update(5, edited).questionForNextWeek()).isEqualTo("Mine?");
        server.verify();
        server.reset();

        server.expect(requestTo("http://backend/api/weekly-reports/5")).andExpect(method(HttpMethod.DELETE)).andRespond(withNoContent());
        client.delete(5);
        server.verify();
    }

    @Test
    void errorsCarryTheBackendMessageAndStatus() {
        server.expect(requestTo("http://backend/api/weekly-reports")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NO_ENTRIES_IN_WEEK\",\"message\":\"There are no finished entries in this week yet.\"}"));
        assertThatThrownBy(() -> client.generate(2031, 10)).isInstanceOfSatisfying(BackendException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.error().code()).isEqualTo("NO_ENTRIES_IN_WEEK");
        });
        server.reset();

        server.expect(requestTo("http://backend/api/weekly-reports/9")).andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("<html>proxy</html>"));
        assertThatThrownBy(() -> client.get(9)).isInstanceOfSatisfying(BackendException.class, e -> {
            assertThat(e.status()).isEqualTo(502);
            assertThat(e.error().code()).isEqualTo("HTTP_502");
        });
    }

    @Test
    void productionConstructorBuildsTheLongTimeoutClient() {
        RestClient rest = RestClient.builder().baseUrl("http://backend").build();
        WeeklyClient real = new WeeklyClient(rest, new ObjectMapper(), java.time.Duration.ofMinutes(5));
        assertThat(real).isNotNull();
        assertThat(List.of(real)).hasSize(1);
    }
}
