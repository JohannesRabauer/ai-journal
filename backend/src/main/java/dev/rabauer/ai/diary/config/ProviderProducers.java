package dev.rabauer.ai.diary.config;

import dev.rabauer.ai.diary.spi.AudioStorage;
import dev.rabauer.ai.diary.spi.EmbeddingGateway;
import dev.rabauer.ai.diary.spi.ExternalProvider;
import dev.rabauer.ai.diary.spi.LlmGateway;
import dev.rabauer.ai.diary.spi.ProviderName;
import dev.rabauer.ai.diary.spi.ReviewScheduler;
import dev.rabauer.ai.diary.spi.SpeechToText;
import dev.rabauer.ai.diary.spi.TextToSpeech;
import dev.rabauer.ai.diary.spi.VectorStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves the unqualified SPI injection points from configuration. Business code injects e.g.
 * {@code @Inject SpeechToText stt;} and never names a concrete provider.
 */
@ApplicationScoped
public class ProviderProducers {

    @Inject
    JournalConfig config;

    @Inject
    @Any
    Instance<SpeechToText> stt;
    @Inject
    @Any
    Instance<TextToSpeech> tts;
    @Inject
    @Any
    Instance<LlmGateway> llm;
    @Inject
    @Any
    Instance<EmbeddingGateway> embedding;
    @Inject
    @Any
    Instance<VectorStore> vector;
    @Inject
    @Any
    Instance<AudioStorage> audio;
    @Inject
    @Any
    Instance<ReviewScheduler> scheduler;

    @Produces
    @ApplicationScoped
    SpeechToText speechToText() {
        return pick(stt, Capability.STT);
    }

    @Produces
    @ApplicationScoped
    TextToSpeech textToSpeech() {
        return pick(tts, Capability.TTS);
    }

    @Produces
    @ApplicationScoped
    LlmGateway llmGateway() {
        return pick(llm, Capability.LLM);
    }

    @Produces
    @ApplicationScoped
    EmbeddingGateway embeddingGateway() {
        return pick(embedding, Capability.EMBEDDING);
    }

    @Produces
    @ApplicationScoped
    VectorStore vectorStore() {
        return pick(vector, Capability.VECTOR);
    }

    @Produces
    @ApplicationScoped
    AudioStorage audioStorage() {
        return pick(audio, Capability.AUDIO);
    }

    @Produces
    @ApplicationScoped
    ReviewScheduler reviewScheduler() {
        return pick(scheduler, Capability.SCHEDULER);
    }

    private <T> T pick(Instance<T> all, Capability c) {
        String name = config.providerFor(c);
        Instance<T> selected = all.select(new ProviderName.Literal(name));
        if (!selected.isResolvable()) {
            throw new IllegalStateException(c.property() + "=" + name + " is not available. Available: " + names(all));
        }
        return selected.get();
    }

    /** The bean instance (not the producer proxy) of the selected provider, e.g. for {@code SelfValidating}. */
    public Optional<Object> selectedInstance(Capability c) {
        Instance<?> selected = instanceFor(c).select(new ProviderName.Literal(config.providerFor(c)));
        return selected.isResolvable() ? Optional.of(selected.get()) : Optional.empty();
    }

    public Map<Capability, ProviderSelection> selections() {
        Map<Capability, ProviderSelection> out = new EnumMap<>(Capability.class);
        for (Capability c : Capability.values()) {
            String name = config.providerFor(c);
            Instance<?> all = instanceFor(c);
            boolean external = false;
            for (var h : all.handles()) {
                for (var q : h.getBean().getQualifiers()) {
                    if (q instanceof ProviderName pn && pn.value().equals(name)
                            && ExternalProvider.class.isAssignableFrom(h.getBean().getBeanClass())) {
                        external = true;
                    }
                }
            }
            out.put(c, new ProviderSelection(c, name, names(all), external));
        }
        return out;
    }

    private Instance<?> instanceFor(Capability c) {
        return switch (c) {
            case STT -> stt;
            case TTS -> tts;
            case LLM -> llm;
            case EMBEDDING -> embedding;
            case VECTOR -> vector;
            case AUDIO -> audio;
            case SCHEDULER -> scheduler;
        };
    }

    private static Set<String> names(Instance<?> all) {
        Set<String> names = new TreeSet<>();
        for (var h : all.handles()) {
            for (var q : h.getBean().getQualifiers()) {
                if (q instanceof ProviderName pn) {
                    names.add(pn.value());
                }
            }
        }
        return names;
    }
}
