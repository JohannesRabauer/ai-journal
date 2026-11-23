package dev.rabauer.ai.diary.spi.fake;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.spi.ProviderName;
import dev.rabauer.ai.diary.spi.SpeechToText;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/** Deterministic STT for tests and AI-free development. Programmable via {@link #respondWith}. */
@ApplicationScoped
@ProviderName("fake")
@Unremovable
public class FakeSpeechToText implements SpeechToText {

    private volatile Function<Request, Result> responder = defaultResponder();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

    public static Function<Request, Result> defaultResponder() {
        return r -> new Result("Fake transcript.", r.language() == Language.AUTO ? Language.EN : r.language(), Duration.ofSeconds(1));
    }

    @Override
    public Result transcribe(Request request) {
        requests.add(request);
        return responder.apply(request);
    }

    /** The function may throw {@link dev.rabauer.ai.diary.spi.ProviderException} to simulate failures. */
    public void respondWith(Function<Request, Result> responder) {
        this.responder = responder;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public void reset() {
        requests.clear();
        responder = defaultResponder();
    }
}
