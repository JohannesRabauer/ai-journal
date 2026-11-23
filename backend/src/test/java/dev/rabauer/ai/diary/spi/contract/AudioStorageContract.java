package dev.rabauer.ai.diary.spi.contract;

import dev.rabauer.ai.diary.spi.AudioStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every AudioStorage implementation must pass this. */
public abstract class AudioStorageContract {

    protected AudioStorage storage;

    protected abstract AudioStorage create() throws Exception;

    @BeforeEach
    void setUpStorage() throws Exception {
        storage = create();
        storage.deleteAll();
    }

    private static ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes());
    }

    @Test
    void chunksAreAssembledInIndexOrderRegardlessOfUploadOrder() throws Exception {
        UUID id = UUID.randomUUID();
        storage.writeChunk(id, 2, bytes("C"));
        storage.writeChunk(id, 0, bytes("A"));
        storage.writeChunk(id, 1, bytes("B"));
        assertThat(storage.chunkIndices(id)).containsExactly(0, 1, 2);
        storage.assemble(id, 3);
        assertThat(storage.hasAudio(id)).isTrue();
        assertThat(storage.audioSize(id)).isEqualTo(3);
        try (var in = storage.openAudio(id)) {
            assertThat(new String(in.readAllBytes())).isEqualTo("ABC");
        }
    }

    @Test
    void rewritingAChunkIsIdempotent() throws Exception {
        UUID id = UUID.randomUUID();
        storage.writeChunk(id, 0, bytes("A"));
        storage.writeChunk(id, 0, bytes("A"));
        assertThat(storage.chunkIndices(id)).containsExactly(0);
    }

    @Test
    void assemblingWithMissingChunkFailsAndKeepsChunks() throws Exception {
        UUID id = UUID.randomUUID();
        storage.writeChunk(id, 0, bytes("A"));
        storage.writeChunk(id, 2, bytes("C"));
        assertThatThrownBy(() -> storage.assemble(id, 3)).isInstanceOf(IllegalStateException.class);
        assertThat(storage.chunkIndices(id)).containsExactly(0, 2);
        assertThat(storage.hasAudio(id)).isFalse();
    }

    @Test
    void unknownRecordingHasNothing() {
        UUID id = UUID.randomUUID();
        assertThat(storage.chunkIndices(id)).isEmpty();
        assertThat(storage.hasAudio(id)).isFalse();
        assertThatThrownBy(() -> storage.openAudio(id)).isInstanceOf(FileNotFoundException.class);
        assertThat(storage.delete(id)).isFalse();
    }

    @Test
    void deleteAndDeleteAllRemoveEverything() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        storage.writeChunk(a, 0, bytes("A"));
        storage.assemble(a, 1);
        storage.writeChunk(b, 0, bytes("B"));
        assertThat(storage.delete(a)).isTrue();
        assertThat(storage.hasAudio(a)).isFalse();
        assertThat(storage.deleteAll()).isEqualTo(1);
        assertThat(storage.chunkIndices(b)).isEmpty();
    }
}
