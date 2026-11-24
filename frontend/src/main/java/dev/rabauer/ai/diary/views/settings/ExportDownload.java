package dev.rabauer.ai.diary.views.settings;

import com.vaadin.flow.server.StreamResourceWriter;
import dev.rabauer.ai.diary.client.PrivacyClient;

/**
 * The export zip is streamed from the backend straight into the browser download: nothing is written to disk on
 * the frontend host, so there is no temp file that could outlive the download (D-0xx, docs/PRIVACY.md).
 */
final class ExportDownload {

    private ExportDownload() {
    }

    static StreamResourceWriter writer(PrivacyClient client, boolean includeAudio) {
        return (out, session) -> {
            try {
                client.export(includeAudio, out);
            } catch (java.io.UncheckedIOException e) {
                throw e.getCause();
            }
        };
    }
}
