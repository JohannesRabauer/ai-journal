package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.rabauer.ai.diary.api.memory.CandidateStatus;
import dev.rabauer.ai.diary.api.memory.MemoryItemType;
import dev.rabauer.ai.diary.api.memory.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MemoryClientTest {

    private static final String CANDIDATE = "{\"id\":4,\"sourceType\":\"ENTRY\",\"sourceId\":7,\"type\":\"FACT\",\"prompt\":\"Q?\","
            + "\"answer\":\"A\",\"sourceQuote\":\"quote\",\"status\":\"%s\",\"createdAt\":\"2026-01-02T10:00:00Z\"}";
    private static final String ITEM = "{\"id\":9,\"type\":\"INSIGHT\",\"prompt\":\"Q?\",\"answer\":\"A\",\"sourceQuote\":null,"
            + "\"sourceType\":null,\"sourceId\":null,\"repetitions\":1,\"intervalDays\":3,\"easeFactor\":2.5,"
            + "\"nextReviewOn\":\"2026-01-05\",\"createdAt\":\"2026-01-02T10:00:00Z\"}";

    private MockRestServiceServer server;
    private MemoryClient memory;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend");
        server = MockRestServiceServer.bindTo(builder).build();
        memory = new MemoryClient(builder.build(), new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    void extractPostsSourceAndParsesCandidates() {
        server.expect(requestTo("http://backend/api/memory/extractions")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"sourceType\":\"CONVERSATION\",\"sourceId\":5}"))
                .andRespond(withSuccess("[" + CANDIDATE.formatted("PENDING") + "]", MediaType.APPLICATION_JSON));
        var list = memory.extract(SourceType.CONVERSATION, 5);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).status()).isEqualTo(CandidateStatus.PENDING);
        assertThat(list.get(0).type()).isEqualTo(MemoryItemType.FACT);
        server.verify();
    }

    @Test
    void candidatesAreFilteredByStatus() {
        server.expect(requestTo("http://backend/api/memory/candidates?status=PENDING")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThat(memory.candidates(CandidateStatus.PENDING)).isEmpty();
        server.verify();
    }

    @Test
    void approveSendsEditsAndDiscardSendsOnlyTheDecision() {
        server.expect(requestTo("http://backend/api/memory/candidates/4/decision")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"decision\":\"APPROVE\",\"prompt\":\"New?\",\"answer\":\"New\",\"type\":\"PRINCIPLE\"}"))
                .andRespond(withSuccess(CANDIDATE.formatted("APPROVED"), MediaType.APPLICATION_JSON));
        assertThat(memory.approve(4, "New?", "New", MemoryItemType.PRINCIPLE).status()).isEqualTo(CandidateStatus.APPROVED);
        server.reset();

        server.expect(requestTo("http://backend/api/memory/candidates/4/decision"))
                .andExpect(content().json("{\"decision\":\"DISCARD\",\"prompt\":null,\"answer\":null,\"type\":null}"))
                .andRespond(withSuccess(CANDIDATE.formatted("DISCARDED"), MediaType.APPLICATION_JSON));
        assertThat(memory.discard(4).status()).isEqualTo(CandidateStatus.DISCARDED);
        server.verify();
    }

    @Test
    void itemsAndDelete() {
        server.expect(requestTo("http://backend/api/memory/items")).andRespond(withSuccess("[" + ITEM + "]", MediaType.APPLICATION_JSON));
        var items = memory.items();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).sourceType()).isNull();
        assertThat(items.get(0).nextReviewOn()).hasToString("2026-01-05");
        server.reset();

        server.expect(requestTo("http://backend/api/memory/items/9")).andExpect(method(HttpMethod.DELETE)).andRespond(withNoContent());
        memory.deleteItem(9);
        server.verify();
    }

    @Test
    void dueAttemptAndGrade() {
        server.expect(requestTo("http://backend/api/memory/reviews/due"))
                .andRespond(withSuccess("[{\"itemId\":9,\"type\":\"FACT\",\"prompt\":\"Q?\"}]", MediaType.APPLICATION_JSON));
        assertThat(memory.due()).hasSize(1);
        server.reset();

        server.expect(requestTo("http://backend/api/memory/reviews/9/attempt")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"attemptText\":null}"))
                .andRespond(withSuccess("{\"itemId\":9,\"answer\":\"A\",\"sourceQuote\":\"q\",\"sourceType\":\"ENTRY\",\"sourceId\":7,\"attemptText\":null}",
                        MediaType.APPLICATION_JSON));
        var reveal = memory.attempt(9, null);
        assertThat(reveal.answer()).isEqualTo("A");
        assertThat(reveal.sourceType()).isEqualTo(SourceType.ENTRY);
        server.reset();

        server.expect(requestTo("http://backend/api/memory/reviews/9/grade")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"quality\":4}"))
                .andRespond(withSuccess(ITEM, MediaType.APPLICATION_JSON));
        assertThat(memory.grade(9, 4).intervalDays()).isEqualTo(3);
        server.verify();
    }

    @Test
    void lessonsSendTheUiLanguage() {
        String body = "[{\"id\":\"retrieval\",\"order\":1,\"title\":\"T\",\"body\":\"B\",\"unlocked\":true,\"unlockHint\":null}]";
        server.expect(requestTo("http://backend/api/memory/lessons")).andExpect(header("Accept-Language", "de"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThat(memory.lessons(Locale.GERMANY)).hasSize(1);
        server.reset();
        server.expect(requestTo("http://backend/api/memory/lessons")).andExpect(header("Accept-Language", "en"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThat(memory.lessons(Locale.FRANCE)).hasSize(1);
        server.verify();
    }

    @Test
    void errorsBecomeBackendExceptionsWithTheBackendMessage() {
        server.expect(requestTo("http://backend/api/memory/reviews/9/grade"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ATTEMPT_REQUIRED\",\"message\":\"Try an answer first.\"}"));
        assertThatThrownBy(() -> memory.grade(9, 4)).isInstanceOfSatisfying(BackendException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.error().code()).isEqualTo("ATTEMPT_REQUIRED");
        });
    }

    @Test
    void extractionFailureIs502WithCode() {
        server.expect(requestTo("http://backend/api/memory/extractions"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"LLM_OUTPUT_INVALID\",\"message\":\"unusable\"}"));
        assertThatThrownBy(() -> memory.extract(SourceType.ENTRY, 1)).isInstanceOfSatisfying(BackendException.class,
                e -> assertThat(e.error().code()).isEqualTo("LLM_OUTPUT_INVALID"));
    }
}
