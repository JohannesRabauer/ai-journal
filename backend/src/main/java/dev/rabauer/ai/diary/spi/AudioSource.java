package dev.rabauer.ai.diary.spi;

import java.io.IOException;
import java.io.InputStream;

/**
 * A complete audio file to transcribe. {@link #open()} may be called more than once (retries).
 */
public record AudioSource(Opener opener, long sizeBytes, String mimeType, String fileName) {

    @FunctionalInterface
    public interface Opener {
        InputStream open() throws IOException;
    }

    public InputStream open() throws IOException {
        return opener.open();
    }
}
