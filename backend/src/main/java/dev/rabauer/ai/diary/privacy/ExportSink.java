package dev.rabauer.ai.diary.privacy;

import dev.rabauer.ai.diary.spi.AudioSource;

import java.io.IOException;

/** Target of a data export (a zip in the real implementation). */
public interface ExportSink {

    /** Serializes {@code value} with the application's ObjectMapper. */
    void json(String path, Object value) throws IOException;

    void binary(String path, AudioSource.Opener content) throws IOException;

    /** Whether the user asked to include audio files. */
    boolean includeAudio();
}
