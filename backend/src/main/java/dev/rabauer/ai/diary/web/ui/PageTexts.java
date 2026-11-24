package dev.rabauer.ai.diary.web.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Texts for JavaScript: the page renders them as JSON into a {@code data-texts} attribute (escaped by Qute), the
 * module reads them with {@code JSON.parse}. The modules contain no texts of their own (docs/MIGRATION-QUTE.md).
 */
public final class PageTexts {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The recorder's labels, short keys as in {@code js/recorder/diary-recorder.js} (entries.recorder.&lt;key&gt;). */
    static final List<String> RECORDER_KEYS = List.of("record", "pause", "resume", "stop", "elapsed", "level", "idle",
            "requesting", "recording", "paused", "stopping", "uploading", "pending", "waiting", "done", "retry",
            "recoverTitle", "recoverText", "finish");

    private PageTexts() {
    }

    /** {@code {"<key>": "<text>"}} for the given message keys; the texts are not formatted (placeholders stay). */
    public static String json(Messages t, String... keys) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : keys) {
            map.put(key, t.get(key));
        }
        return write(map);
    }

    public static String recorderLabels(Messages t) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : RECORDER_KEYS) {
            map.put(key, t.get("entries.recorder." + key));
        }
        return write(map);
    }

    /** Labels of {@code <diary-audio-player>}; the position text keeps its {0} and {1} placeholders. */
    public static String audioLabels(Messages t) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("group", t.get("entries.audio.label"));
        map.put("play", t.get("entries.audio.play"));
        map.put("pause", t.get("entries.audio.pause"));
        map.put("seek", t.get("entries.audio.seek"));
        map.put("position", t.get("entries.audio.position", "{0}", "{1}"));
        map.put("error", t.get("entries.audio.error"));
        return write(map);
    }

    private static String write(Map<String, String> map) {
        try {
            return JSON.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("texts not serializable", e);
        }
    }

    /** Texts shared by the editor panel (transcription progress, review, finish) on Today and the entry page. */
    static final String[] PANEL_KEYS = {
            "entries.transcription.running", "entries.transcription.failed", "entries.transcription.retry",
            "entries.transcription.typeManually", "entries.transcription.stillRunning", "entries.review.label",
            "entries.review.helper", "entries.text.label", "entries.finish", "entries.finished",
            "entries.autosave.saving", "entries.autosave.saved", "entries.autosave.failed", "entries.autosave.retry",
            "entries.error.unreachable"
    };
}
