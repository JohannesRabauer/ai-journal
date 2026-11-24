package dev.rabauer.ai.diary.tts;

import dev.rabauer.ai.diary.privacy.ExportSink;
import dev.rabauer.ai.diary.privacy.UserDataOwner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The audio cache holds derived journal content (spoken coach questions). Delete-all clears it; the cache entries are
 * not linked to entries, so there is no per-entry observer and nothing to export (derived, regenerable data).
 */
@ApplicationScoped
public class TtsDataOwner implements UserDataOwner {

    @Inject
    TtsCache cache;

    @Override
    public String name() {
        return "tts-cache";
    }

    @Override
    public int deleteOrder() {
        return 2000;
    }

    @Override
    public long deleteAll() {
        return cache.clear();
    }

    @Override
    public void export(ExportSink sink) {
        // derived, regenerable audio: not exported
    }
}
