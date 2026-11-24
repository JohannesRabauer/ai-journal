package dev.rabauer.ai.diary.views.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Earlier versions wrote the export zip to a temp file that was deleted only after the download. A crash or an
 * abandoned download left a full copy of the journal in the temp directory; remove such leftovers at startup.
 */
@Component
public class ExportTempSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(ExportTempSweeper.class);

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        int n = sweep(Path.of(System.getProperty("java.io.tmpdir")));
        if (n > 0) {
            LOG.info("Removed {} leftover export file(s) from the temp directory", n);
        }
    }

    /** Deletes {@code ai-diary-export-*.zip} files directly in {@code dir}; returns how many were removed. */
    static int sweep(Path dir) {
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "ai-diary-export-*.zip")) {
            for (Path f : files) {
                try {
                    if (Files.isRegularFile(f) && Files.deleteIfExists(f)) {
                        removed++;
                    }
                } catch (IOException ignored) {
                    // in use or not ours to delete; leave it
                }
            }
        } catch (IOException ignored) {
            // temp directory not readable: nothing to sweep
        }
        return removed;
    }
}
