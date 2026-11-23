package dev.rabauer.ai.diary.spi.fake;

import dev.rabauer.ai.diary.spi.LlmGateway;
import dev.rabauer.ai.diary.spi.ProviderName;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Scriptable LLM for deterministic tests. Queue responses with {@link #enqueue}; every call consumes one.
 * When the queue is empty, {@code chat} returns {@link #DEFAULT_CHAT} and {@code chatJson} returns "{}".
 * All requests are recorded for prompt-contract assertions ({@link #calls()}).
 */
@ApplicationScoped
@ProviderName("fake")
@Unremovable
public class FakeLlmGateway implements LlmGateway {

    public static final String DEFAULT_CHAT = "Fake reply.";

    public record Call(Request request, JsonSchema schema) {
        public boolean structured() {
            return schema != null;
        }
    }

    private final Deque<Object> queue = new ArrayDeque<>();
    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    @Override
    public synchronized String chat(Request request) {
        calls.add(new Call(request, null));
        return next(DEFAULT_CHAT);
    }

    @Override
    public synchronized String chatJson(Request request, JsonSchema schema) {
        calls.add(new Call(request, schema));
        return next("{}");
    }

    private String next(String fallback) {
        Object o = queue.pollFirst();
        if (o == null) {
            return fallback;
        }
        if (o instanceof RuntimeException e) {
            throw e;
        }
        return (String) o;
    }

    /** Queue a raw response (JSON or text). */
    public synchronized FakeLlmGateway enqueue(String response) {
        queue.addLast(response);
        return this;
    }

    /** Queue a failure, e.g. a {@link dev.rabauer.ai.diary.spi.ProviderException}. */
    public synchronized FakeLlmGateway enqueueFailure(RuntimeException failure) {
        queue.addLast(failure);
        return this;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public synchronized void reset() {
        queue.clear();
        calls.clear();
    }
}
