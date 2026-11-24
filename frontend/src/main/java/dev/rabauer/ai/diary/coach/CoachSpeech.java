package dev.rabauer.ai.diary.coach;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DomEvent;
import com.vaadin.flow.component.EventData;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.shared.Registration;
import dev.rabauer.ai.diary.api.common.Language;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Wrapper for {@code <diary-coach-speech>}: plays coach questions through the TTS proxy, announces new questions
 * politely and moves focus, all deferred while any recording is active (SPEC 5.8). Methods are only called as a
 * reaction to a user action; nothing here creates content.
 */
@Tag("diary-coach-speech")
@JsModule("./coach/coach-speech.js")
public class CoachSpeech extends Component {

    private static final ObjectMapper JSON = new ObjectMapper();

    public CoachSpeech(Map<String, String> labels) {
        try {
            getElement().setAttribute("labels", JSON.writeValueAsString(labels));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("labels not serializable", e);
        }
    }

    /** Queue a question for automatic playback (waits while recording). */
    public void enqueue(long id, String text, Language language, String voice) {
        getElement().callJsFunction("enqueue", id, text, language.name(), voice);
    }

    /** Explicit play button. */
    public void play(long id, String text, Language language, String voice) {
        getElement().callJsFunction("play", id, text, language.name(), voice);
    }

    public void stop() {
        getElement().callJsFunction("stop");
    }

    public void announce(String text) {
        getElement().callJsFunction("announce", text);
    }

    public void focusWhenIdle(Component target) {
        getElement().callJsFunction("focusWhenIdle", target.getElement());
    }

    /** Asks the browser whether any recording is active right now. */
    public void isRecording(Consumer<Boolean> answer) {
        getElement().callJsFunction("isRecording").then(Boolean.class, answer::accept);
    }

    public Registration addRecordingChangedListener(ComponentEventListener<RecordingChangedEvent> listener) {
        return addListener(RecordingChangedEvent.class, listener);
    }

    @DomEvent("recording-changed")
    public static class RecordingChangedEvent extends ComponentEvent<CoachSpeech> {
        private final boolean recording;

        public RecordingChangedEvent(CoachSpeech source, boolean fromClient,
                                     @EventData("event.detail.recording") boolean recording) {
            super(source, fromClient);
            this.recording = recording;
        }

        public boolean recording() {
            return recording;
        }
    }
}
