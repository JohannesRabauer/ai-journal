package dev.rabauer.ai.diary.recorder;

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
import java.util.UUID;

/**
 * Wrapper for the {@code <diary-recorder>} web component (voice capture, chunked resumable upload through the
 * same-origin proxy). It never shows or triggers AI output.
 */
@Tag("diary-recorder")
@JsModule("./recorder/diary-recorder.js")
public class RecorderComponent extends Component {

    private static final ObjectMapper JSON = new ObjectMapper();

    public RecorderComponent(Map<String, String> labels) {
        setLabels(labels);
    }

    /** Overrides the built-in English texts (keys as in {@code diary-recorder.js}). */
    public final void setLabels(Map<String, String> labels) {
        try {
            getElement().setAttribute("labels", JSON.writeValueAsString(labels));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("labels not serializable", e);
        }
    }

    public void setLanguage(Language language) {
        getElement().setAttribute("language", language == null ? "auto" : language.code());
    }

    public Registration addRecordingStartedListener(ComponentEventListener<RecordingStartedEvent> listener) {
        return addListener(RecordingStartedEvent.class, listener);
    }

    public Registration addRecordingCompletedListener(ComponentEventListener<RecordingCompletedEvent> listener) {
        return addListener(RecordingCompletedEvent.class, listener);
    }

    public Registration addUploadErrorListener(ComponentEventListener<UploadErrorEvent> listener) {
        return addListener(UploadErrorEvent.class, listener);
    }

    @DomEvent("recording-started")
    public static class RecordingStartedEvent extends ComponentEvent<RecorderComponent> {
        private final String recordingId;
        private final long entryId;

        public RecordingStartedEvent(RecorderComponent source, boolean fromClient,
                                     @EventData("event.detail.recordingId") String recordingId,
                                     @EventData("event.detail.entryId") double entryId) {
            super(source, fromClient);
            this.recordingId = recordingId;
            this.entryId = (long) entryId;
        }

        public UUID recordingId() {
            return UUID.fromString(recordingId);
        }

        public long entryId() {
            return entryId;
        }
    }

    @DomEvent("recording-completed")
    public static class RecordingCompletedEvent extends ComponentEvent<RecorderComponent> {
        private final String recordingId;
        private final long entryId;
        private final long durationMs;

        public RecordingCompletedEvent(RecorderComponent source, boolean fromClient,
                                       @EventData("event.detail.recordingId") String recordingId,
                                       @EventData("event.detail.entryId") double entryId,
                                       @EventData("event.detail.durationMs") double durationMs) {
            super(source, fromClient);
            this.recordingId = recordingId;
            this.entryId = (long) entryId;
            this.durationMs = (long) durationMs;
        }

        public UUID recordingId() {
            return UUID.fromString(recordingId);
        }

        public long entryId() {
            return entryId;
        }

        public long durationMs() {
            return durationMs;
        }
    }

    /** Upload or microphone problem; the message is user-presentable and never contains journal content. */
    @DomEvent("upload-error")
    public static class UploadErrorEvent extends ComponentEvent<RecorderComponent> {
        private final String kind;
        private final String message;

        public UploadErrorEvent(RecorderComponent source, boolean fromClient,
                                @EventData("event.detail.kind") String kind,
                                @EventData("event.detail.message") String message) {
            super(source, fromClient);
            this.kind = kind;
            this.message = message;
        }

        public String kind() {
            return kind;
        }

        public String message() {
            return message;
        }
    }
}
