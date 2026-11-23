package dev.rabauer.ai.diary.views.entries;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.shared.Registration;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.api.transcription.TranscriptionJobDto;
import dev.rabauer.ai.diary.client.BackendException;
import dev.rabauer.ai.diary.client.EntriesClient;
import dev.rabauer.ai.diary.client.TranscriptionsClient;
import dev.rabauer.ai.diary.views.today.TranscriptionPolling;
import dev.rabauer.ai.diary.views.today.TranscriptionPolling.Decision;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shows an entry according to its lifecycle state: transcription progress, calm failure notice with retry / type it
 * yourself, the editable text (transcript review) and "Finish entry". Contains no AI output; the only background
 * work it waits for is transcription.
 */
public class EntryEditorPanel extends Composite<VerticalLayout> {

    private final EntriesClient entries;
    private final TranscriptionsClient transcriptions;
    private final Texts texts;
    private final Supplier<Language> language;
    private final Consumer<EntryDto> onChanged;

    private EntryDto entry;
    private boolean hasAudio;
    private boolean manualMode;
    private AutosaveEditor editor;
    private Registration pollRegistration;
    private int polls;

    /** @param onChanged called when the entry reached a new state worth refreshing lists for (e.g. finished) */
    public EntryEditorPanel(EntriesClient entries, TranscriptionsClient transcriptions, Texts texts,
                            Supplier<Language> language, Consumer<EntryDto> onChanged) {
        this.entries = entries;
        this.transcriptions = transcriptions;
        this.texts = texts;
        this.language = language;
        this.onChanged = onChanged;
        VerticalLayout root = getContent();
        root.setPadding(false);
        root.setSpacing(true);
    }

    public void show(EntryDto entry, boolean hasAudio) {
        this.entry = entry;
        this.hasAudio = hasAudio;
        render();
    }

    public EntryDto entry() {
        return entry;
    }

    /** Saves pending text; true if nothing is left unsaved. */
    public boolean saveNow() {
        return editor == null || editor.save();
    }

    private void render() {
        stopPolling();
        VerticalLayout root = getContent();
        root.removeAll();
        editor = null;
        if (entry == null) {
            return;
        }
        EntryActions actions = EntryActions.forEntry(entry.status(), hasAudio, true, manualMode);

        if (actions.transcribing()) {
            Paragraph p = new Paragraph(texts.get("entries.transcription.running"));
            p.getElement().setAttribute("role", "status");
            ProgressBar bar = new ProgressBar();
            bar.setIndeterminate(true);
            bar.getElement().setAttribute("aria-label", texts.get("entries.transcription.running"));
            root.add(p, bar);
            startPolling();
            return;
        }

        if (actions.retryTranscription() || actions.typeManually()) {
            Div notice = new Div(new Paragraph(texts.get("entries.transcription.failed")));
            notice.getElement().setAttribute("role", "status");
            root.add(notice);
            HorizontalLayout row = new HorizontalLayout();
            if (actions.retryTranscription()) {
                Button retry = new Button(texts.get("entries.transcription.retry"), e -> retryTranscription());
                retry.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
                row.add(retry);
            }
            if (actions.typeManually()) {
                row.add(new Button(texts.get("entries.transcription.typeManually"), e -> {
                    manualMode = true;
                    render();
                    editor.textArea().focus();
                }));
            }
            root.add(row);
        }

        if (actions.editText()) {
            boolean reviewing = entry.status() == EntryStatus.TRANSCRIBED;
            editor = new AutosaveEditor(entries, texts, language, AutosavePresenter.forExisting(entry.id(), entry.text()),
                    entry.text(), saved -> entry = saved);
            editor.textArea().setLabel(texts.get(reviewing ? "entries.review.label" : "entries.text.label"));
            if (reviewing) {
                editor.textArea().setHelperText(texts.get("entries.review.helper"));
            }
            root.add(editor);
            EntryActions finishRule = EntryActions.forEntry(entry.status(), hasAudio, true, manualMode);
            if (finishRule.finish()) {
                Button finish = new Button(texts.get("entries.finish"), e -> finish());
                finish.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
                finish.setEnabled(!editor.value().isBlank());
                editor.textArea().addValueChangeListener(e -> finish.setEnabled(!e.getValue().isBlank()));
                root.add(finish);
            }
        }
    }

    private void retryTranscription() {
        try {
            transcriptions.start(entry.id(), language.get());
            show(entries.get(entry.id()), hasAudio);
        } catch (BackendException e) {
            Notification.show(e.error().message());
        } catch (RuntimeException e) {
            Notification.show(texts.get("entries.error.unreachable"));
        }
    }

    private void finish() {
        if (!saveNow()) {
            Notification.show(texts.get("entries.autosave.failed"));
            return;
        }
        try {
            EntryDto finished = entries.finish(entry.id());
            show(finished, hasAudio);
            onChanged.accept(finished);
        } catch (BackendException e) {
            Notification.show(e.error().message());
        } catch (RuntimeException e) {
            Notification.show(texts.get("entries.error.unreachable"));
        }
    }

    // ---- transcription polling (stops on success, failure, give-up or detach)

    private void startPolling() {
        UI ui = UI.getCurrent();
        if (ui == null || pollRegistration != null) {
            return;
        }
        polls = 0;
        ui.setPollInterval(TranscriptionPolling.INTERVAL_MS);
        pollRegistration = ui.addPollListener(e -> poll());
    }

    private void stopPolling() {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
            UI ui = UI.getCurrent();
            if (ui != null) {
                ui.setPollInterval(-1);
            }
        }
    }

    private void poll() {
        polls++;
        try {
            EntryDto current = entries.get(entry.id());
            Optional<TranscriptionJobDto> job = transcriptions.latest(entry.id());
            Decision decision = TranscriptionPolling.decide(current.status(), job, polls);
            switch (decision) {
                case CONTINUE -> {
                }
                case SUCCEEDED, FAILED -> {
                    show(current, true);
                    onChanged.accept(current);
                }
                case GIVE_UP -> {
                    stopPolling();
                    getContent().removeAll();
                    getContent().add(new Paragraph(texts.get("entries.transcription.stillRunning")));
                }
            }
        } catch (RuntimeException e) {
            // transient problem: keep polling until the bounded limit
            if (polls >= TranscriptionPolling.MAX_POLLS) {
                stopPolling();
            }
        }
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        stopPolling();
        super.onDetach(detachEvent);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (entry != null && pollRegistration == null
                && TranscriptionPolling.isInProgress(entry.status())) {
            startPolling();
        }
    }
}
