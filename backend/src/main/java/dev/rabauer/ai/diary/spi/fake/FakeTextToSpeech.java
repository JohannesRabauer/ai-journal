package dev.rabauer.ai.diary.spi.fake;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.spi.ProviderName;
import dev.rabauer.ai.diary.spi.TextToSpeech;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

@ApplicationScoped
@ProviderName("fake")
@Unremovable
public class FakeTextToSpeech implements TextToSpeech {

    private volatile Function<Request, Audio> responder = r -> new Audio(("FAKEWAV:" + r.text().length()).getBytes(StandardCharsets.US_ASCII), "audio/wav");
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

    @Override
    public Audio synthesize(Request request) {
        requests.add(request);
        return responder.apply(request);
    }

    @Override
    public List<Voice> voices() {
        return List.of(new Voice("fake-de", Language.DE, "Fake (de)"), new Voice("fake-en", Language.EN, "Fake (en)"));
    }

    public void respondWith(Function<Request, Audio> responder) {
        this.responder = responder;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }
}
