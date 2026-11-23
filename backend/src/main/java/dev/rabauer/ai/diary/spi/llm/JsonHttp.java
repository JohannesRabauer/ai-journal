package dev.rabauer.ai.diary.spi.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.spi.ProviderException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON-over-HTTP client shared by the Ollama and OpenAI chat and embedding gateways.
 * Maps every failure to a {@link ProviderException} (UNREACHABLE / ERROR_RESPONSE / REJECTED / BAD_RESPONSE)
 * and never puts request or response bodies into messages or logs (they contain journal content).
 */
public final class JsonHttp {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String provider;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient client;

    public JsonHttp(String provider, String baseUrl, Duration timeout) {
        this.provider = provider;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** POSTs {@code body} as JSON to {@code baseUrl + path} and returns the parsed JSON response. */
    public JsonNode post(String path, Object body, Map<String, String> headers) {
        String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ProviderException(provider, ProviderException.Kind.REJECTED, "request could not be serialized");
        }
        HttpRequest.Builder rb;
        try {
            rb = HttpRequest.newBuilder(new URI(baseUrl + path));
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new ProviderException(provider, ProviderException.Kind.REJECTED, "configured base URL is not valid");
        }
        rb.timeout(timeout).header("Content-Type", "application/json").header("Accept", "application/json");
        headers.forEach(rb::header);
        HttpResponse<byte[]> resp;
        try {
            resp = client.send(rb.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(provider, ProviderException.Kind.UNREACHABLE, "request interrupted", e);
        } catch (IOException e) {
            // connect failure, timeout, connection reset, DNS: all "unreachable, try later"
            throw new ProviderException(provider, ProviderException.Kind.UNREACHABLE,
                    "provider not reachable (" + e.getClass().getSimpleName() + ")", e);
        }
        int status = resp.statusCode();
        if (status == 429 || status >= 500) {
            throw new ProviderException(provider, ProviderException.Kind.ERROR_RESPONSE, "provider answered HTTP " + status);
        }
        if (status < 200 || status >= 300) {
            throw new ProviderException(provider, ProviderException.Kind.REJECTED, "provider rejected the request with HTTP " + status);
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(resp.body());
        } catch (IOException e) {
            throw new ProviderException(provider, ProviderException.Kind.BAD_RESPONSE, "response is not valid JSON");
        }
        if (node == null || node.isMissingNode() || node.isNull()) {
            throw new ProviderException(provider, ProviderException.Kind.BAD_RESPONSE, "empty response body");
        }
        return node;
    }

    /** Extracts {@code [[float...], ...]} with exactly {@code expected} vectors, or throws BAD_RESPONSE. */
    public List<float[]> vectors(JsonNode arrayOfArrays, int expected) {
        if (arrayOfArrays == null || !arrayOfArrays.isArray() || arrayOfArrays.size() != expected) {
            throw new ProviderException(provider, ProviderException.Kind.BAD_RESPONSE, "unexpected number of embeddings");
        }
        List<float[]> out = new ArrayList<>(expected);
        for (JsonNode v : arrayOfArrays) {
            out.add(vector(v));
        }
        return out;
    }

    public float[] vector(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            throw new ProviderException(provider, ProviderException.Kind.BAD_RESPONSE, "embedding is missing or empty");
        }
        float[] f = new float[array.size()];
        for (int i = 0; i < f.length; i++) {
            if (!array.get(i).isNumber()) {
                throw new ProviderException(provider, ProviderException.Kind.BAD_RESPONSE, "embedding contains a non-number");
            }
            f[i] = (float) array.get(i).asDouble();
        }
        return f;
    }

    /** Validates an http(s) base URL; returns an error text or null. No network. */
    public static String checkUrl(String url) {
        try {
            URI u = new URI(url);
            if (u.getHost() == null || (!"http".equals(u.getScheme()) && !"https".equals(u.getScheme()))) {
                return "is not a valid http(s) URL";
            }
            return null;
        } catch (URISyntaxException e) {
            return "is not a valid URL: " + e.getReason();
        }
    }
}
