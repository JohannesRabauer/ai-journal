package dev.rabauer.ai.diary.audio;

import dev.rabauer.ai.diary.spi.AudioStorage;
import dev.rabauer.ai.diary.spi.ProviderName;
import dev.rabauer.ai.diary.spi.SelfValidating;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Stores recordings below one directory: {@code <dir>/<recording-uuid>/chunk-<n>.part} and {@code audio.bin}.
 * Every file is written to a temporary name and moved into place atomically, so a crash never leaves a
 * half-written chunk that looks complete. Paths are built from UUIDs and integers only (no traversal possible).
 */
@ApplicationScoped
@ProviderName("filesystem")
@Unremovable
public class FilesystemAudioStorage implements AudioStorage, SelfValidating {

    static final int MAX_CHUNK_INDEX = 1_000_000;
    private static final Pattern CHUNK = Pattern.compile("chunk-([0-9]+)[.]part");
    private static final String AUDIO = "audio.bin";

    @ConfigProperty(name = "journal.audio.filesystem.directory", defaultValue = "./data/audio")
    String directory = "./data/audio";

    public FilesystemAudioStorage() {
    }

    public FilesystemAudioStorage(Path directory) {
        this.directory = directory.toString();
    }

    private Path root() {
        return Path.of(directory).toAbsolutePath().normalize();
    }

    private Path dir(UUID id) {
        return root().resolve(id.toString());
    }

    @Override
    public void validateConfiguration(List<String> errors) {
        try {
            Path p = Path.of(directory);
            if (Files.exists(p) && !Files.isDirectory(p)) {
                errors.add("journal.audio.filesystem.directory is not a directory: " + directory);
            }
        } catch (RuntimeException e) {
            errors.add("journal.audio.filesystem.directory is not a valid path: " + directory);
        }
    }

    @Override
    public void writeChunk(UUID recordingId, int chunkIndex, InputStream data) throws IOException {
        if (chunkIndex < 0 || chunkIndex > MAX_CHUNK_INDEX) {
            throw new IllegalArgumentException("chunkIndex out of range");
        }
        Path d = dir(recordingId);
        Files.createDirectories(d);
        Path tmp = Files.createTempFile(d, "chunk-" + chunkIndex + "-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING)) {
                data.transferTo(out);
            }
            move(tmp, d.resolve("chunk-" + chunkIndex + ".part"));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Override
    public List<Integer> chunkIndices(UUID recordingId) {
        Path d = dir(recordingId);
        List<Integer> out = new ArrayList<>();
        if (!Files.isDirectory(d)) {
            return out;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(d, "chunk-*.part")) {
            for (Path f : files) {
                Matcher m = CHUNK.matcher(f.getFileName().toString());
                if (m.matches()) {
                    out.add(Integer.parseInt(m.group(1)));
                }
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        out.sort(Integer::compare);
        return out;
    }

    @Override
    public void assemble(UUID recordingId, int totalChunks) throws IOException {
        List<Integer> have = chunkIndices(recordingId);
        if (have.isEmpty() && hasAudio(recordingId)) {
            return; // already assembled (retry after a crash between assembling and bookkeeping)
        }
        for (int i = 0; i < totalChunks; i++) {
            if (!have.contains(i)) {
                throw new IllegalStateException("missing chunk " + i);
            }
        }
        Path d = dir(recordingId);
        Path tmp = Files.createTempFile(d, "audio-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (int i = 0; i < totalChunks; i++) {
                    Files.copy(d.resolve("chunk-" + i + ".part"), out);
                }
            }
            move(tmp, d.resolve(AUDIO));
        } finally {
            Files.deleteIfExists(tmp);
        }
        for (int i : have) {
            Files.deleteIfExists(d.resolve("chunk-" + i + ".part"));
        }
    }

    @Override
    public boolean hasAudio(UUID recordingId) {
        return Files.isRegularFile(dir(recordingId).resolve(AUDIO));
    }

    @Override
    public InputStream openAudio(UUID recordingId) throws IOException {
        Path f = dir(recordingId).resolve(AUDIO);
        if (!Files.isRegularFile(f)) {
            throw new FileNotFoundException("no audio for recording " + recordingId);
        }
        return Files.newInputStream(f);
    }

    @Override
    public long audioSize(UUID recordingId) {
        try {
            Path f = dir(recordingId).resolve(AUDIO);
            return Files.isRegularFile(f) ? Files.size(f) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public boolean delete(UUID recordingId) {
        Path d = dir(recordingId);
        if (!Files.isDirectory(d)) {
            return false;
        }
        deleteTree(d);
        return true;
    }

    @Override
    public long deleteAll() {
        Path r = root();
        if (!Files.isDirectory(r)) {
            return 0;
        }
        long n = 0;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(r)) {
            for (Path c : children) {
                if (Files.isDirectory(c) && isUuid(c.getFileName().toString())) {
                    deleteTree(c);
                    n++;
                }
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return n;
    }

    private static boolean isUuid(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
