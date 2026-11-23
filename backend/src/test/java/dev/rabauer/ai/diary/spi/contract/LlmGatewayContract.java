package dev.rabauer.ai.diary.spi.contract;

import dev.rabauer.ai.diary.spi.LlmGateway;
import dev.rabauer.ai.diary.spi.ProviderException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public abstract class LlmGatewayContract {

    protected static final LlmGateway.JsonSchema SCHEMA = new LlmGateway.JsonSchema("q",
            "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}");

    protected abstract LlmGateway create();

    protected abstract void arrangeChat(String reply);

    /** The provider returns this raw JSON text as the message content. */
    protected abstract void arrangeJson(String json);

    protected abstract void arrangeUnreachable();

    private static LlmGateway.Request request() {
        return LlmGateway.Request.of(List.of(LlmGateway.Message.system("Ask one question."), LlmGateway.Message.user("Today I walked.")));
    }

    @Test
    void chatReturnsText() {
        arrangeChat("Where did you walk?");
        assertThat(create().chat(request())).isEqualTo("Where did you walk?");
    }

    @Test
    void chatJsonReturnsRawJsonUnvalidated() {
        arrangeJson("{\"q\":\"Where?\"}");
        assertThat(create().chatJson(request(), SCHEMA)).isEqualTo("{\"q\":\"Where?\"}");
    }

    @Test
    void unreachableRaisesProviderException() {
        arrangeUnreachable();
        assertThatThrownBy(() -> create().chat(request())).isInstanceOf(ProviderException.class);
    }
}
