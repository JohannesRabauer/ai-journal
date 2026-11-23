package dev.rabauer.ai.diary.spi.tts;

import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.ProviderName;
import dev.rabauer.ai.diary.spi.TextToSpeech;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Speech output switched off ({@code journal.tts.provider=none}). Every synthesis request fails with
 * {@link ProviderException.Kind#REJECTED}, which callers handle as "show text only" (SPEC section 9).
 */
@ApplicationScoped
@ProviderName("none")
@Unremovable
public class NoTextToSpeech implements TextToSpeech {

    @Override
    public Audio synthesize(Request request) {
        throw new ProviderException("none", ProviderException.Kind.REJECTED, "speech output is switched off");
    }

    @Override
    public List<Voice> voices() {
        return List.of();
    }
}
