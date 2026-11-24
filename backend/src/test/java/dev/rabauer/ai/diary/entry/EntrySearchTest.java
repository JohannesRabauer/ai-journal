package dev.rabauer.ai.diary.entry;

import dev.rabauer.ai.diary.api.common.PageDto;
import dev.rabauer.ai.diary.api.entry.CreateEntryRequest;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.coach.CoachConversationEntity;
import dev.rabauer.ai.diary.coach.CoachConversationRepository;
import dev.rabauer.ai.diary.api.coach.ConversationStatus;
import dev.rabauer.ai.diary.web.ApiException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/** Search and filters of {@code GET /api/entries} (q, source, reflected): service and REST level. */
@QuarkusTest
class EntrySearchTest {

    @Inject
    EntryService service;
    @Inject
    EntryRepository repository;
    @Inject
    CoachConversationRepository conversations;

    private long river;
    private long voiceMeeting;
    private long reflectedWalk;
    private long percent;

    @BeforeEach
    void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            conversations.deleteAll();
            repository.deleteAll();
        });
        LocalDateTime t = LocalDateTime.of(2026, 11, 1, 8, 0);
        river = add("A walk by the River Rhine", t, EntrySource.TEXT);
        voiceMeeting = add("Meeting notes, spoken", t.plusDays(1), EntrySource.VOICE);
        reflectedWalk = add("Long walk, then a coach talk", t.plusDays(2), EntrySource.VOICE);
        percent = add("Saved 100% of it_all, path C:\\tmp!", t.plusDays(3), EntrySource.TEXT);
        QuarkusTransaction.requiringNew().run(() -> {
            CoachConversationEntity c = new CoachConversationEntity();
            c.entryId = reflectedWalk;
            c.status = ConversationStatus.ENDED;
            c.language = "en";
            c.createdAt = Instant.now();
            c.updatedAt = c.createdAt;
            conversations.persist(c);
        });
    }

    private long add(String text, LocalDateTime when, EntrySource source) {
        return service.create(new CreateEntryRequest(text, when, null, source)).id();
    }

    private static List<Long> ids(PageDto<EntryDto> page) {
        return page.items().stream().map(EntryDto::id).toList();
    }

    @Test
    void noParametersBehaveAsBefore() {
        PageDto<EntryDto> all = service.list(0, 20);
        assertThat(ids(all)).containsExactly(percent, reflectedWalk, voiceMeeting, river);
        assertThat(all.total()).isEqualTo(4);
        assertThat(ids(service.list(0, 20, null, null, null))).isEqualTo(ids(all));
        assertThat(ids(service.list(0, 20, "  ", "", null))).isEqualTo(ids(all));
        given().get("/api/entries").then().statusCode(200).body("total", equalTo(4)).body("items", hasSize(4));
    }

    @Test
    void textSearchIsCaseInsensitiveAndTrimmed() {
        assertThat(ids(service.list(0, 20, "  RIVER ", null, null))).containsExactly(river);
        assertThat(ids(service.list(0, 20, "walk", null, null))).containsExactly(reflectedWalk, river);
    }

    @Test
    void sourceFilter() {
        assertThat(ids(service.list(0, 20, null, "VOICE", null))).containsExactly(reflectedWalk, voiceMeeting);
        assertThat(ids(service.list(0, 20, null, "text", null))).containsExactly(percent, river);
    }

    @Test
    void reflectedFilter() {
        assertThat(ids(service.list(0, 20, null, null, true))).containsExactly(reflectedWalk);
        assertThat(ids(service.list(0, 20, null, null, false))).containsExactly(percent, voiceMeeting, river);
    }

    @Test
    void filtersCombineWithAnd() {
        assertThat(ids(service.list(0, 20, "walk", "VOICE", true))).containsExactly(reflectedWalk);
        assertThat(ids(service.list(0, 20, "walk", "VOICE", false))).isEmpty();
        assertThat(ids(service.list(0, 20, "walk", "TEXT", null))).containsExactly(river);
    }

    @Test
    void likeWildcardsAndEscapeCharacterAreLiteral() {
        assertThat(ids(service.list(0, 20, "%", null, null))).containsExactly(percent);
        assertThat(ids(service.list(0, 20, "_", null, null))).containsExactly(percent);
        assertThat(ids(service.list(0, 20, "it_all", null, null))).containsExactly(percent);
        assertThat(ids(service.list(0, 20, "itXall", null, null))).isEmpty();
        assertThat(ids(service.list(0, 20, "100%", null, null))).containsExactly(percent);
        assertThat(ids(service.list(0, 20, "!", null, null))).containsExactly(percent);
        assertThat(ids(service.list(0, 20, "\\", null, null))).containsExactly(percent);
        assertThat(ids(service.list(0, 20, "' or 1=1 --", null, null))).isEmpty();
    }

    @Test
    void emptyResultHasZeroTotal() {
        PageDto<EntryDto> none = service.list(0, 20, "does-not-exist", null, null);
        assertThat(none.items()).isEmpty();
        assertThat(none.total()).isZero();
    }

    @Test
    void pagingAndTotalReflectTheFilter() {
        for (int i = 0; i < 5; i++) {
            add("bulk " + i, LocalDateTime.of(2026, 10, 1, 8, i), EntrySource.TEXT);
        }
        PageDto<EntryDto> first = service.list(0, 2, "bulk", null, null);
        assertThat(first.total()).isEqualTo(5);
        assertThat(first.items()).hasSize(2);
        assertThat(service.list(2, 2, "bulk", null, null).items()).hasSize(1);
        assertThat(service.list(0, 20, null, "VOICE", null).total()).isEqualTo(2);
    }

    @Test
    void invalidInputIsRejected() {
        assertThatThrownBy(() -> service.list(0, 20, null, "AUDIO", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.list(0, 20, "x".repeat(201), null, null)).isInstanceOf(ApiException.class);
        assertThat(service.list(0, 20, "x".repeat(200), null, null).items()).isEmpty();
    }

    @Test
    void restParameters() {
        given().queryParam("q", "river").get("/api/entries").then().statusCode(200)
                .body("total", equalTo(1)).body("items[0].id", equalTo((int) river));
        given().queryParam("source", "VOICE").get("/api/entries").then().statusCode(200).body("total", equalTo(2));
        given().queryParam("reflected", "true").get("/api/entries").then().statusCode(200)
                .body("total", equalTo(1)).body("items[0].hasCoachConversation", equalTo(true));
        given().queryParam("reflected", "false").queryParam("source", "VOICE").get("/api/entries").then()
                .statusCode(200).body("items.id", org.hamcrest.Matchers.contains((int) voiceMeeting));
        given().queryParam("q", "100%").get("/api/entries").then().statusCode(200).body("total", equalTo(1));
        given().queryParam("q", "nothing here").get("/api/entries").then().statusCode(200)
                .body("total", equalTo(0)).body("items", hasSize(0));
        given().queryParam("source", "AUDIO").get("/api/entries").then().statusCode(400)
                .body("code", equalTo("INVALID_SOURCE"));
        given().queryParam("q", "x".repeat(201)).get("/api/entries").then().statusCode(400);
    }

    @Test
    void repositoryLikePatternEscapes() {
        assertThat(EntryRepository.likePattern("A_b%c!")).isEqualTo("%a!_b!%c!!%");
    }
}
