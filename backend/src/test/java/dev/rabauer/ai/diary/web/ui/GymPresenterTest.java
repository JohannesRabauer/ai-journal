package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.gym.AttemptDto;
import dev.rabauer.ai.diary.api.gym.GymCategory;
import dev.rabauer.ai.diary.api.system.SystemInfoDto;
import dev.rabauer.ai.diary.web.ApiException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class GymPresenterTest {

    private static ApiException failure(int status, String code) {
        return new ApiException(Response.Status.fromStatusCode(status), code, "message");
    }

    private static AttemptDto attempt(long id, List<String> questions) {
        return new AttemptDto(id, 1, GymCategory.REFRAMING, "answer " + id, 60, questions, Instant.parse("2026-01-02T10:00:00Z"));
    }

    @Test
    void errorsAreCalmAndRetryableOnlyWhereRepeatingHelps() {
        assertThat(GymPresenter.errorFor(failure(503, "PROVIDER_UNREACHABLE"))).isEqualTo(new GymPresenter.ErrorInfo("gym.error.aiUnavailable", true));
        assertThat(GymPresenter.errorFor(failure(502, "LLM_OUTPUT_INVALID"))).isEqualTo(new GymPresenter.ErrorInfo("gym.error.aiInvalid", true));
        assertThat(GymPresenter.errorFor(failure(400, "ANSWER_EMPTY")).retryable()).isFalse();
        assertThat(GymPresenter.errorFor(failure(400, "ANSWER_TOO_LONG")).messageKey()).isEqualTo("gym.error.answerTooLong");
        assertThat(GymPresenter.errorFor(failure(404, "NOT_FOUND")).messageKey()).isEqualTo("gym.error.notFound");
        assertThat(GymPresenter.errorFor(failure(501, "NOT_IMPLEMENTED")).retryable()).isFalse();
        assertThat(GymPresenter.errorFor(failure(500, "HTTP_500")).retryable()).isTrue();
        assertThat(GymPresenter.errorFor(new IllegalStateException("x"))).isEqualTo(new GymPresenter.ErrorInfo("gym.error.unreachable", true));
    }

    @Test
    void followUpQuestionsAreOfferedOnlyWithARealLlmProvider() {
        assertThat(GymPresenter.llmAvailable(info("ollama"))).isTrue();
        assertThat(GymPresenter.llmAvailable(info("openai"))).isTrue();
        assertThat(GymPresenter.llmAvailable(info("fake"))).isFalse();
        assertThat(GymPresenter.llmAvailable(info("none"))).isFalse();
        assertThat(GymPresenter.llmAvailable(info(" "))).isFalse();
        assertThat(GymPresenter.llmAvailable(new SystemInfoDto("full", false, null, Map.of("stt", "whisper")))).isFalse();
        assertThat(GymPresenter.llmAvailable(new SystemInfoDto("full", false, null, null))).isFalse();
        assertThat(GymPresenter.llmAvailable(null)).isFalse();
    }

    private static SystemInfoDto info(String llm) {
        return new SystemInfoDto("full", false, null, Map.of("llm", llm));
    }

    @Test
    void durationIsNeverNegativeAndCapped() {
        Instant start = Instant.parse("2026-01-02T10:00:00Z");
        assertThat(GymPresenter.durationSeconds(start, start.plusSeconds(125))).isEqualTo(125);
        assertThat(GymPresenter.durationSeconds(start, start.minusSeconds(5))).isZero();
        assertThat(GymPresenter.durationSeconds(start, start.plusSeconds(10 * 3600))).isEqualTo(GymPresenter.MAX_DURATION_SECONDS);
        assertThat(GymPresenter.durationSeconds(null, start)).isZero();
    }

    @Test
    void requestIsNullForABlankAnswerAndTrimsOtherwise() {
        assertThat(GymPresenter.request("   \n", 30)).isNull();
        assertThat(GymPresenter.request(null, 30)).isNull();
        var request = GymPresenter.request("  my thoughts \n", 95);
        assertThat(request.answer()).isEqualTo("my thoughts");
        assertThat(request.durationSeconds()).isEqualTo(95);
        assertThat(GymPresenter.request("x", -4).durationSeconds()).isZero();
    }

    @Test
    void clockAndMinutesFormat() {
        assertThat(GymPresenter.clock(0)).isEqualTo("0:00");
        assertThat(GymPresenter.clock(65)).isEqualTo("1:05");
        assertThat(GymPresenter.clock(754)).isEqualTo("12:34");
        assertThat(GymPresenter.clock(-3)).isEqualTo("0:00");
        assertThat(GymPresenter.minutes(59)).isZero();
        assertThat(GymPresenter.minutes(600)).isEqualTo(10);
    }

    @Test
    void snippetCollapsesWhitespaceAndShortens() {
        assertThat(GymPresenter.snippet("one\n\n two   three", 50)).isEqualTo("one two three");
        assertThat(GymPresenter.snippet("abcdefghij", 5)).isEqualTo("abcd…");
        assertThat(GymPresenter.snippet(" ", 5)).isEmpty();
        assertThat(GymPresenter.snippet(null, 5)).isEmpty();
    }

    @Test
    void languageTagIsGermanOrEnglish() {
        assertThat(GymPresenter.languageTag(Locale.GERMANY)).isEqualTo("de");
        assertThat(GymPresenter.languageTag(Locale.FRENCH)).isEqualTo("en");
        assertThat(GymPresenter.languageTag(null)).isEqualTo("en");
    }

    @Test
    void timeIsFormattedInTheGivenZoneAndLocale() {
        assertThat(GymPresenter.time(Instant.parse("2026-01-02T10:30:00Z"), ZoneId.of("UTC"), Locale.GERMANY)).contains("02.01.2026").contains("10:30");
        assertThat(GymPresenter.time(null, ZoneId.of("UTC"), Locale.GERMANY)).isEmpty();
    }

    @Test
    void historyPagingShowsTheNewestFirstAndOffersMoreWithoutCounting() {
        List<AttemptDto> all = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            all.add(attempt(i, List.of()));
        }
        assertThat(GymPresenter.visible(all, GymPresenter.HISTORY_PAGE)).hasSize(20).first().extracting(AttemptDto::id).isEqualTo(1L);
        assertThat(GymPresenter.hasMore(all, GymPresenter.HISTORY_PAGE)).isTrue();
        assertThat(GymPresenter.visible(all, 40)).hasSize(25);
        assertThat(GymPresenter.hasMore(all, 40)).isFalse();
        assertThat(GymPresenter.visible(null, 20)).isEmpty();
        assertThat(GymPresenter.hasMore(null, 20)).isFalse();
    }

    @Test
    void feedbackDetection() {
        assertThat(GymPresenter.hasFeedback(attempt(1, List.of("Was?")))).isTrue();
        assertThat(GymPresenter.hasFeedback(attempt(1, List.of()))).isFalse();
        assertThat(GymPresenter.hasFeedback(attempt(1, null))).isFalse();
        assertThat(GymPresenter.hasFeedback(null)).isFalse();
    }

    // ------------------------------------------------------------------ i18n bundle

    @Test
    void germanAndEnglishBundlesHaveTheSameKeysAndCoverEveryCategory() throws IOException {
        ClassLoader cl = getClass().getClassLoader();
        Set<String> en = keys(cl, "i18n/gym_en.properties");
        Set<String> de = keys(cl, "i18n/gym_de.properties");
        assertThat(en).isNotEmpty();
        assertThat(de).isEqualTo(en);

        MessageCatalog catalog = MessageCatalog.fromClasspath(cl);
        for (GymCategory category : GymCategory.values()) {
            for (Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
                assertThat(catalog.get(locale, GymPresenter.categoryKey(category))).isNotEqualTo(GymPresenter.categoryKey(category));
                assertThat(catalog.get(locale, GymPresenter.descriptionKey(category))).isNotEqualTo(GymPresenter.descriptionKey(category));
            }
        }
        assertThat(catalog.get(Locale.GERMAN, "gym.title")).isEqualTo("Denktraining");
        for (String key : List.of("gym.error.unreachable", "gym.error.aiUnavailable", "gym.error.aiInvalid", "gym.error.answerEmpty",
                "gym.error.answerTooLong", "gym.error.notFound", "gym.error.unavailable")) {
            assertThat(en).contains(key);
        }
    }

    @Test
    void noScoreStreakOrCountWordingInTheBundles() throws IOException {
        for (String lang : List.of("en", "de")) {
            Properties p = load(getClass().getClassLoader(), "i18n/gym_" + lang + ".properties");
            String all = String.join("\n", p.stringPropertyNames().stream().map(k -> p.getProperty(k).toLowerCase(Locale.ROOT)).toList());
            for (String banned : List.of("streak", "score", "points", "punkte", "serie", "completed", "abgeschlossen", "level", "badge")) {
                assertThat(all).as(lang + " bundle must not mention '" + banned + "'").doesNotContain(banned);
            }
        }
    }

    private static Set<String> keys(ClassLoader cl, String name) throws IOException {
        return new TreeSet<>(load(cl, name).stringPropertyNames());
    }

    private static Properties load(ClassLoader cl, String name) throws IOException {
        Properties p = new Properties();
        Enumeration<URL> e = cl.getResources(name);
        while (e.hasMoreElements()) {
            try (var reader = new InputStreamReader(e.nextElement().openStream(), StandardCharsets.UTF_8)) {
                p.load(reader);
            }
        }
        return p;
    }
}
