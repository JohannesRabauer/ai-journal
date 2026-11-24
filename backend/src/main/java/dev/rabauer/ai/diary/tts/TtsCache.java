package dev.rabauer.ai.diary.tts;

import dev.rabauer.ai.diary.spi.TextToSpeech;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * File-based cache of synthesized audio. One file per entry named {@code <sha256>.<subtype>} (the content type is
 * {@code audio/<subtype>}), written atomically; bounded to {@code max-entries} with LRU eviction (the file's modified
 * time is the last access time). The cache holds derived journal content and is wiped by delete-all ({@link TtsDataOwner}).
 */
@ApplicationScoped
public class TtsCache {

    @ConfigProperty(name = "journal.tts.cache.directory", defaultValue = "./data/tts-cache")
    String directory = "./data/tts-cache";
    @ConfigProperty(name = "journal.tts.cache.max-entries", defaultValue = "500")
    int maxEntries = 500;

    public TtsCache() {
    }

    TtsCache(Path directory, int maxEntries) {
        this.directory = directory.toString();
        this.maxEntries = maxEntries;
    }

    /** sha256 over provider, voice, language and text (never logged). */
    public static String key(String provider, String voice, String language, String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{provider, voice == null ? "" : voice, language, text}) {
                md.update(part.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path dir() {
        return Path.of(directory);
    }

    public synchronized Optional<TextToSpeech.Audio> get(String key) {
        try {
            Path dir = dir();
            if (!Files.isDirectory(dir)) {
                return Optional.empty();
            }
            try (Stream<Path> s = Files.list(dir)) {
                Optional<Path> hit = s.filter(p -> p.getFileName().toString().startsWith(key + ".")
                        && !p.getFileName().toString().endsWith(".tmp")).findFirst();
                if (hit.isEmpty()) {
                    return Optional.empty();
                }
                Path file = hit.get();
                byte[] data = Files.readAllBytes(file);
                Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
                String name = file.getFileName().toString();
                return Optional.of(new TextToSpeech.Audio(data, "audio/" + name.substring(key.length() + 1)));
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Stores audio/* results; other content types are not cached. Failures are swallowed (the cache is optional). */
    public synchronized void put(String key, TextToSpeech.Audio audio) {
        String subtype = subtype(audio.contentType());
        if (subtype == null || maxEntries < 1) {
            return;
        }
        try {
            Path dir = dir();
            Files.createDirectories(dir);
            Path tmp = dir.resolve(UUID.randomUUID() + ".tmp");
            Files.write(tmp, audio.data());
            Path target = dir.resolve(key + "." + subtype);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            evict(dir);
        } catch (IOException e) {
            // cache is best effort
        }
    }

    private void evict(Path dir) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> !p.getFileName().toString().endsWith(".tmp")).sorted(
                    Comparator.comparing((Path p) -> mtime(p)).thenComparing(p -> p.getFileName().toString())).toList();
        }
        for (int i = 0; i < files.size() - maxEntries; i++) {
            Files.deleteIfExists(files.get(i));
        }
    }

    private static FileTime mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized long size() {
        Path dir = dir();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> !p.getFileName().toString().endsWith(".tmp")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** @return number of files removed */
    public synchronized long clear() {
        Path dir = dir();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        long n = 0;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                if (Files.isRegularFile(p) && Files.deleteIfExists(p)) {
                    n++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return n;
    }

    /** {@code audio/wav; charset=x} becomes {@code wav}; null when not an audio type or not a safe file suffix. */
    static String subtype(String contentType) {
        if (contentType == null) {
            return null;
        }
        String t = contentType.toLowerCase(Locale.ROOT);
        int i = t.indexOf(';');
        t = (i < 0 ? t : t.substring(0, i)).strip();
        if (!t.startsWith("audio/")) {
            return null;
        }
        String sub = t.substring(6);
        return sub.matches("[a-z0-9][a-z0-9.+-]{0,30}") ? sub : null;
    }
}
