package dev.rabauer.ai.diary.recorder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Collects the recorder's translated texts ({@code entries.recorder.<key>}). Missing translations keep the built-in English. */
public final class RecorderLabels {

    static final List<String> KEYS = List.of("record", "pause", "resume", "stop", "elapsed", "level", "idle", "requesting",
            "recording", "paused", "stopping", "uploading", "pending", "waiting", "done", "retry", "recoverTitle",
            "recoverText", "continue", "finish");

    private RecorderLabels() {
    }

    public static Map<String, String> from(Function<String, String> translate) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String key : KEYS) {
            String value = translate.apply("entries.recorder." + key);
            // Vaadin renders "!{key}!" for a missing translation; keep the web component's English default then.
            if (value != null && !value.isBlank() && !value.startsWith("!{") && !value.contains("entries.recorder.")) {
                labels.put(key, value);
            }
        }
        return labels;
    }
}
