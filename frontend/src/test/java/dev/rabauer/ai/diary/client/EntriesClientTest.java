package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.rabauer.ai.diary.api.entry.CreateEntryRequest;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EntriesClientTest {

    private MockRestServiceServer server;
    private EntriesClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend");
        server = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        client = new EntriesClient(builder.build(), mapper);
    }

    @Test
    void createPostsJsonAndParsesEntry() {
        server.expect(requestTo("http://backend/api/entries")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"text\":\"hello\",\"source\":\"TEXT\"}"))
                .andRespond(withSuccess("{\"id\":7,\"status\":\"DRAFT\",\"source\":\"TEXT\",\"text\":\"hello\",\"hasCoachConversation\":false}",
                        MediaType.APPLICATION_JSON));
        var e = client.create(new CreateEntryRequest("hello", null, null, EntrySource.TEXT));
        assertThat(e.id()).isEqualTo(7);
        assertThat(e.status()).isEqualTo(EntryStatus.DRAFT);
        server.verify();
    }

    @Test
    void listRequestsPage() {
        server.expect(requestTo("http://backend/api/entries?page=1&size=5"))
                .andRespond(withSuccess("{\"items\":[],\"page\":1,\"size\":5,\"total\":5}", MediaType.APPLICATION_JSON));
        assertThat(client.list(1, 5).total()).isEqualTo(5);
    }

    @Test
    void backendErrorsBecomeBackendExceptionWithUserMessage() {
        server.expect(requestTo("http://backend/api/entries/9/finish"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ENTRY_BUSY\",\"message\":\"The entry is still being recorded or transcribed.\"}"));
        assertThatThrownBy(() -> client.finish(9))
                .isInstanceOfSatisfying(BackendException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(409);
                    assertThat(ex.error().code()).isEqualTo("ENTRY_BUSY");
                });
    }

    @Test
    void nonJsonErrorsGetAGenericMessage() {
        server.expect(requestTo("http://backend/api/entries/1")).andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("<html>"));
        assertThatThrownBy(() -> client.get(1)).isInstanceOfSatisfying(BackendException.class,
                ex -> assertThat(ex.error().code()).isEqualTo("HTTP_502"));
    }
}
