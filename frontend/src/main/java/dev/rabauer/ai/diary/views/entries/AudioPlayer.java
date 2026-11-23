package dev.rabauer.ai.diary.views.entries;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.Tag;

import java.util.UUID;

/** Native {@code <audio controls>} streaming from the same-origin proxy (supports seeking via Range). */
@Tag("audio")
public class AudioPlayer extends Component implements HasSize {

    public AudioPlayer(UUID recordingId, String label) {
        getElement().setAttribute("controls", true);
        getElement().setAttribute("preload", "none");
        getElement().setAttribute("src", "/upload/recordings/" + recordingId + "/audio");
        getElement().setAttribute("aria-label", label);
        setWidthFull();
    }
}
