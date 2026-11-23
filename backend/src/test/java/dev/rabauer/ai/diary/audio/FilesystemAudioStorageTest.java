package dev.rabauer.ai.diary.audio;

import dev.rabauer.ai.diary.spi.AudioStorage;
import dev.rabauer.ai.diary.spi.contract.AudioStorageContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilesystemAudioStorageTest extends AudioStorageContract {

    @TempDir
    Path dir;

    @Override
    protected AudioStorage create() {
        return new FilesystemAudioStorage(dir);
    }

    private static ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes());
    }

    @Test
    void writesOneFolderPerRecordingAndLeavesNoTempFiles() throws Exception {
        UUID id = UUID.randomUUID();
        storage.writeChunk(id, 0, bytes("A"));
        storage.writeChunk(id, 1, bytes("B"));
        try (Stream<Path> s = Files.list(dir.resolve(id.toString()))) {
            assertThat(s.map(p -> p.getFileName().toString()).toList()).containsExactlyInAnyOrder("chunk-0.part", "chunk-1.part");
        }
        storage.assemble(id, 2);
        try (Stream<Path> s = Files.list(dir.resolve(id.toString()))) {
            assertThat(s.map(p -> p.getFileName().toString()).toList()).containsExactly("audio.bin");
        }
    }

    @Test
    void assembleIsIdempotentAfterAudioExists() throws Exception {
        UUID id = UUID.randomUUID();
        storage.writeChunk(id, 0, bytes("A"));
        storage.assemble(id, 1);
        storage.assemble(id, 1);
        assertThat(storage.audioSize(id)).isEqualTo(1);
    }

    @Test
    void rejectsOutOfRangeChunkIndex() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> storage.writeChunk(id, -1, bytes(""))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.writeChunk(id, Integer.MAX_VALUE, bytes(""))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteAllIgnoresForeignFolders() throws Exception {
        Files.createDirectories(dir.resolve("not-a-uuid"));
        UUID id = UUID.randomUUID();
        storage.writeChunk(id, 0, bytes("A"));
        assertThat(storage.deleteAll()).isEqualTo(1);
        assertThat(dir.resolve("not-a-uuid")).exists();
    }

    @Test
    void reportsFileAsInvalidDirectory() throws Exception {
        Path file = Files.createFile(dir.resolve("afile"));
        List<String> errors = new ArrayList<>();
        new FilesystemAudioStorage(file).validateConfiguration(errors);
        assertThat(errors).hasSize(1);
    }
}
