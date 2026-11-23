package dev.rabauer.ai.diary.privacy;

import dev.rabauer.ai.diary.spi.AudioStorage;
import dev.rabauer.ai.diary.spi.VectorStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.io.IOException;

/** Registers the non-DB stores (vectors, audio) for delete-all. Audio export is done by the recordings owner. */
@ApplicationScoped
public class StoreDataOwners {

    @Inject
    VectorStore vectors;
    @Inject
    AudioStorage audio;

    @Produces
    @ApplicationScoped
    UserDataOwner vectorOwner() {
        return new UserDataOwner() {
            @Override
            public String name() {
                return "vectors";
            }

            @Override
            public int deleteOrder() {
                return 2000;
            }

            @Override
            public long deleteAll() {
                return vectors.deleteAll();
            }

            @Override
            public void export(ExportSink sink) {
                // derived data, rebuilt from entries; not exported
            }
        };
    }

    @Produces
    @ApplicationScoped
    UserDataOwner audioOwner() {
        return new UserDataOwner() {
            @Override
            public String name() {
                return "audio-files";
            }

            @Override
            public int deleteOrder() {
                return 2000;
            }

            @Override
            public long deleteAll() {
                return audio.deleteAll();
            }

            @Override
            public void export(ExportSink sink) throws IOException {
                // exported per recording by the recordings feature (W1a), which knows the ids
            }
        };
    }
}
