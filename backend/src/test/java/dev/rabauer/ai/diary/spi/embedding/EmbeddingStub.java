package dev.rabauer.ai.diary.spi.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import java.io.IOException;

/** WireMock transformer that answers an embeddings request with one fixed vector per input text. */
final class EmbeddingStub implements ResponseTransformerV2 {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean openAiStyle;
    private volatile float[] vector = {0.1f, 0.2f, 0.3f};

    EmbeddingStub(boolean openAiStyle) {
        this.openAiStyle = openAiStyle;
    }

    void vector(float[] v) {
        this.vector = v;
    }

    @Override
    public Response transform(Response response, ServeEvent serveEvent) {
        try {
            JsonNode input = JSON.readTree(serveEvent.getRequest().getBodyAsString()).path("input");
            ArrayNode v = JSON.createArrayNode();
            for (float f : vector) {
                v.add(f);
            }
            ObjectNode out = JSON.createObjectNode();
            ArrayNode list = out.putArray(openAiStyle ? "data" : "embeddings");
            for (int i = 0; i < input.size(); i++) {
                if (openAiStyle) {
                    // answer in reverse order to prove the gateway honours "index"
                    list.insert(0, JSON.createObjectNode().put("object", "embedding").put("index", i).set("embedding", v));
                } else {
                    list.add(v);
                }
            }
            return Response.Builder.like(response).but().body(JSON.writeValueAsString(out)).build();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String getName() {
        return "embedding-stub";
    }

    @Override
    public boolean applyGlobally() {
        return false;
    }
}
