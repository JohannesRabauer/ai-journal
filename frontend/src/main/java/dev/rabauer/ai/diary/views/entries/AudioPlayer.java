package dev.rabauer.ai.diary.views.entries;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.HasStyle;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;

import java.util.Map;
import java.util.UUID;

/**
 * Player card: round play/pause button, keyboard operable seek slider and time, driving an {@code <audio>} that
 * streams from the same-origin proxy (seeking via Range). See {@code entries/audio-player.js}.
 */
@Tag("diary-audio-player")
@JsModule("./entries/audio-player.js")
public class AudioPlayer extends Component implements HasSize, HasStyle {

    private static final ObjectMapper JSON = new ObjectMapper();

    public AudioPlayer(UUID recordingId, Texts texts) {
        getElement().setAttribute("src", "/upload/recordings/" + recordingId + "/audio");
        try {
            getElement().setAttribute("labels", JSON.writeValueAsString(Map.of(
                    "group", texts.get("entries.audio.label"),
                    "play", texts.get("entries.audio.play"),
                    "pause", texts.get("entries.audio.pause"),
                    "seek", texts.get("entries.audio.seek"),
                    "position", texts.get("entries.audio.position", "{0}", "{1}"),
                    "error", texts.get("entries.audio.error"))));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("labels not serializable", e);
        }
        addClassNames("diary-card", "entries-player");
    }
}
