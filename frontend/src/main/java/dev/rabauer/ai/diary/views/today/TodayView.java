package dev.rabauer.ai.diary.views.today;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.client.BackendException;
import dev.rabauer.ai.diary.client.EntriesClient;
import dev.rabauer.ai.diary.client.TranscriptionsClient;
import dev.rabauer.ai.diary.recorder.RecorderComponent;
import dev.rabauer.ai.diary.recorder.RecorderLabels;
import dev.rabauer.ai.diary.shell.MainLayout;
import dev.rabauer.ai.diary.views.entries.AutosaveEditor;
import dev.rabauer.ai.diary.views.entries.AutosavePresenter;
import dev.rabauer.ai.diary.views.entries.BrowserZone;
import dev.rabauer.ai.diary.views.entries.EntryDetailView;
import dev.rabauer.ai.diary.views.entries.EntryListPresenter;
import dev.rabauer.ai.diary.views.entries.EntryEditorPanel;

import java.util.List;

/**
 * Today: record or write a new entry, review the transcript, finish. Hard rule (SPEC 4.4): there is no AI output
 * anywhere on this screen; the only background work is transcription of the user's own recording.
 */
@Route(value = "", layout = MainLayout.class)
public class TodayView extends VerticalLayout implements HasDynamicTitle {

    private final EntriesClient entries;
    private final Select<Language> language = new Select<>();
    private final AutosaveEditor writeEditor;
    private final EntryEditorPanel reviewPanel;
    private final VerticalLayout reviewSection = new VerticalLayout();
    private final Paragraph message = new Paragraph();
    private final VerticalLayout todayList = new VerticalLayout();

    public TodayView(EntriesClient entries, TranscriptionsClient transcriptions) {
        this.entries = entries;
        setMaxWidth("48rem");

        language.setLabel(getTranslation("entries.language.label"));
        language.setItems(Language.values());
        language.setItemLabelGenerator(l -> getTranslation("entries.language." + l.name()));
        language.setValue(Language.AUTO);

        RecorderComponent recorder = new RecorderComponent(RecorderLabels.from(this::getTranslation));
        recorder.addRecordingCompletedListener(e -> onRecordingCompleted(e.entryId()));
        language.addValueChangeListener(e -> recorder.setLanguage(e.getValue()));

        writeEditor = new AutosaveEditor(entries, this::getTranslation, language::getValue,
                AutosavePresenter.forNewEntry(), "", saved -> { });
        writeEditor.textArea().setLabel(getTranslation("entries.write.label"));
        writeEditor.textArea().setHelperText(getTranslation("entries.write.helper"));
        Button finishText = new Button(getTranslation("entries.finish"), e -> finishTextEntry());
        finishText.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        reviewPanel = new EntryEditorPanel(entries, transcriptions, this::getTranslation, language::getValue,
                this::onReviewChanged);
        reviewSection.setPadding(false);
        reviewSection.add(new H3(getTranslation("entries.review.title")), reviewPanel);
        reviewSection.setVisible(false);

        message.getElement().setAttribute("role", "status");
        todayList.setPadding(false);
        todayList.setSpacing(false);

        add(new H2(getTranslation("entries.today.title")),
                language,
                recorder,
                reviewSection,
                new H3(getTranslation("entries.write.title")),
                writeEditor, finishText,
                message,
                new H3(getTranslation("entries.today.list")),
                todayList);
        refreshToday();
    }

    @Override
    protected void onAttach(com.vaadin.flow.component.AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        // Learn the browser time zone so new entries carry the user's local time; refresh "today" once known.
        BrowserZone.init(attachEvent.getUI(), this::refreshToday);
    }

    @Override
    public String getPageTitle() {
        return getTranslation("entries.today.title");
    }

    private void onRecordingCompleted(long entryId) {
        try {
            EntryDto entry = entries.get(entryId);
            message.setText("");
            reviewSection.setVisible(true);
            reviewPanel.show(entry, true);
            refreshToday();
        } catch (RuntimeException e) {
            Notification.show(getTranslation("entries.error.unreachable"));
        }
    }

    private void onReviewChanged(EntryDto entry) {
        if (entry.status() == EntryStatus.FINISHED) {
            reviewSection.setVisible(false);
            message.setText(getTranslation("entries.finished"));
        }
        refreshToday();
    }

    private void finishTextEntry() {
        if (!writeEditor.save()) {
            Notification.show(getTranslation("entries.autosave.failed"));
            return;
        }
        Long id = writeEditor.entryId();
        if (id == null) {
            return;
        }
        try {
            entries.finish(id);
            writeEditor.startNewEntry();
            message.setText(getTranslation("entries.finished"));
            refreshToday();
        } catch (BackendException e) {
            Notification.show(e.error().message());
        } catch (RuntimeException e) {
            Notification.show(getTranslation("entries.error.unreachable"));
        }
    }

    private void refreshToday() {
        todayList.removeAll();
        try {
            List<EntryDto> today = TodayEntries.of(entries.list(0, 50).items(), BrowserZone.current().today());
            if (today.isEmpty()) {
                todayList.add(new Paragraph(getTranslation("entries.today.none")));
            }
            for (EntryDto e : today) {
                var row = EntryListPresenter.row(e, getLocale());
                String label = row.preview().isBlank() ? row.date() : row.date() + " · " + row.preview();
                todayList.add(EntryDetailView.link(label, e.id()));
            }
        } catch (RuntimeException e) {
            todayList.add(new Paragraph(getTranslation("entries.journal.loadFailed")));
        }
    }
}
