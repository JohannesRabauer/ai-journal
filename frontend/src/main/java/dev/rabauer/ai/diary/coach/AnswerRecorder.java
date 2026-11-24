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

/**
 * Wrapper for {@code <diary-answer-recorder>}: a short spoken answer, transcribed through the same-origin proxy and
 * handed back as text for review. Nothing is stored; no AI output is produced here.
 */
@Tag("diary-answer-recorder")
@JsModule("./coach/diary-answer-recorder.js")
public class AnswerRecorder extends Component {

    private static final ObjectMapper JSON = new ObjectMapper();

    public AnswerRecorder(Map<String, String> labels) {
        try {
            getElement().setAttribute("labels", JSON.writeValueAsString(labels));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("labels not serializable", e);
        }
    }

    public void setLanguage(Language language) {
        getElement().setAttribute("language", language == null ? "auto" : language.code());
    }

    public Registration addTranscribedListener(ComponentEventListener<TranscribedEvent> listener) {
        return addListener(TranscribedEvent.class, listener);
    }

    public Registration addBusyListener(ComponentEventListener<BusyEvent> listener) {
        return addListener(BusyEvent.class, listener);
    }

    /** The transcript is user content: never log it. */
    @DomEvent("answer-transcribed")
    public static class TranscribedEvent extends ComponentEvent<AnswerRecorder> {
        private final String text;

        public TranscribedEvent(AnswerRecorder source, boolean fromClient,
                                @EventData("event.detail.text") String text) {
            super(source, fromClient);
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /** True while the browser is recording or transcribing. */
    @DomEvent("answer-busy")
    public static class BusyEvent extends ComponentEvent<AnswerRecorder> {
        private final boolean busy;

        public BusyEvent(AnswerRecorder source, boolean fromClient, @EventData("event.detail.busy") boolean busy) {
            super(source, fromClient);
            this.busy = busy;
        }

        public boolean busy() {
            return busy;
        }
    }
}
