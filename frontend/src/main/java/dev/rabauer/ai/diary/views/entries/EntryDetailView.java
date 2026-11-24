package dev.rabauer.ai.diary.views.entries;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;
import dev.rabauer.ai.diary.api.audio.RecordingDto;
import dev.rabauer.ai.diary.api.audio.RecordingStatus;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.api.memory.SourceType;
import dev.rabauer.ai.diary.client.BackendException;
import dev.rabauer.ai.diary.client.EntriesClient;
import dev.rabauer.ai.diary.client.RecordingsClient;
import dev.rabauer.ai.diary.client.TranscriptionsClient;
import dev.rabauer.ai.diary.shell.MainLayout;
import dev.rabauer.ai.diary.views.coach.CoachView;
import dev.rabauer.ai.diary.views.memory.MemoryView;

/**
 * Entry detail: text edit with autosave, audio playback through the proxy, delete audio / delete entry (with
 * confirmation), finish. "Reflect" opens the coach for finished entries.
 */
@Route(value = "entries/:id", layout = MainLayout.class)
public class EntryDetailView extends VerticalLayout implements BeforeEnterObserver, HasDynamicTitle {

    private final EntriesClient entries;
    private final RecordingsClient recordings;
    private final TranscriptionsClient transcriptions;

    public EntryDetailView(EntriesClient entries, RecordingsClient recordings, TranscriptionsClient transcriptions) {
        this.entries = entries;
        this.recordings = recordings;
        this.transcriptions = transcriptions;
        setMaxWidth("48rem");
    }

    public static RouterLink link(String text, long entryId) {
        return new RouterLink(text, EntryDetailView.class, new RouteParameters("id", String.valueOf(entryId)));
    }

    @Override
    public String getPageTitle() {
        return getTranslation("entries.detail.title");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        long id;
        try {
            id = Long.parseLong(event.getRouteParameters().get("id").orElse(""));
        } catch (NumberFormatException e) {
            showMessage("entries.detail.notFound");
            return;
        }
        load(id, false);
    }

    /** @param refocus after an in-page action the old controls are gone, so focus moves to the entry heading */
    private void load(long id, boolean refocus) {
        removeAll();
        add(new RouterLink(getTranslation("entries.detail.back"), EntriesView.class));
        EntryDto entry;
        try {
            entry = entries.get(id);
        } catch (BackendException e) {
            showMessage(e.status() == 404 ? "entries.detail.notFound" : "entries.error.unreachable");
            return;
        } catch (RuntimeException e) {
            showMessage("entries.error.unreachable");
            return;
        }

        RecordingDto recording = null;
        if (entry.recordingId() != null) {
            try {
                recording = recordings.get(entry.recordingId());
            } catch (RuntimeException e) {
                // audio status unknown: show the entry without a player
            }
        }
        boolean hasAudio = recording != null && recording.status() == RecordingStatus.COMPLETE;
        EntryActions actions = EntryActions.forEntry(entry.status(), hasAudio, !blank(entry.text()), false);

        EntryListPresenter.Row row = EntryListPresenter.row(entry, UI.getCurrent().getLocale());
        H1 heading = new H1(row.date());
        heading.getElement().setAttribute("tabindex", "-1");
        HorizontalLayout head = new HorizontalLayout(heading, new Span(getTranslation(row.statusKey())));
        head.setAlignItems(FlexComponent.Alignment.BASELINE);
        head.getStyle().set("flex-wrap", "wrap"); // reflow at 320px (WCAG 1.4.10)
        add(head);
        if (refocus) {
            heading.getElement().executeJs("this.focus()");
        }
        if (entry.source() == EntrySource.VOICE) {
            Span voice = new Span(getTranslation("entries.badge.voice"));
            voice.getElement().getThemeList().add("badge contrast");
            add(voice);
        }

        if (hasAudio) {
            add(new AudioPlayer(entry.recordingId(), getTranslation("entries.audio.label")));
            if (actions.deleteAudio()) {
                Button deleteAudio = new Button(getTranslation("entries.audio.delete"),
                        e -> confirm("entries.audio.delete.title", "entries.audio.delete.text",
                                "entries.audio.delete", () -> deleteAudio(entry)));
                deleteAudio.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
                add(deleteAudio);
            }
        } else if (recording != null && recording.status() == RecordingStatus.AUDIO_DELETED) {
            add(new Paragraph(getTranslation("entries.audio.deleted")));
        }

        EntryEditorPanel panel = new EntryEditorPanel(entries, transcriptions, this::getTranslation,
                () -> Language.AUTO, changed -> load(changed.id(), true));
        panel.show(entry, hasAudio);
        add(panel);

        if (entry.legacyAiRemark() != null && !entry.legacyAiRemark().isBlank()) {
            Details remark = new Details(getTranslation("entries.legacyRemark"), new Paragraph(entry.legacyAiRemark()));
            remark.setOpened(false);
            add(remark);
        }

        HorizontalLayout footer = new HorizontalLayout();
        footer.getStyle().set("flex-wrap", "wrap");
        boolean finished = entry.status() == EntryStatus.FINISHED;
        Button reflect = new Button(getTranslation(entry.hasCoachConversation() ? "coach.entry.reflect.open" : "entries.reflect"),
                e -> UI.getCurrent().navigate(CoachView.class, new RouteParameters("id", String.valueOf(entry.id()))));
        reflect.setEnabled(finished);
        if (!finished) {
            reflect.getElement().setAttribute("title", getTranslation("coach.notFinished"));
        }
        footer.add(reflect);
        if (finished && entry.hasCoachConversation()) {
            footer.add(new Span(getTranslation("coach.entry.hasConversation")));
        }
        if (finished && !blank(entry.text())) {
            Button extract = new Button(getTranslation("memory.extractLearnings"),
                    e -> MemoryView.openWithExtraction(UI.getCurrent(), SourceType.ENTRY, entry.id()));
            extract.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            extract.getElement().setAttribute("title", getTranslation("memory.extractLearnings.title"));
            footer.add(extract);
        }
        if (actions.deleteEntry()) {
            Button delete = new Button(getTranslation("entries.delete"),
                    e -> confirm("entries.delete.title", "entries.delete.text", "entries.delete", () -> deleteEntry(entry)));
            delete.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
            footer.add(delete);
        }
        add(footer);
    }

    private void confirm(String headerKey, String textKey, String confirmKey, Runnable action) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader(getTranslation(headerKey));
        dialog.setText(getTranslation(textKey));
        dialog.setCancelable(true);
        dialog.setCancelText(getTranslation("entries.cancel"));
        dialog.setConfirmText(getTranslation(confirmKey));
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(e -> action.run());
        dialog.open();
    }

    private void deleteAudio(EntryDto entry) {
        try {
            recordings.deleteAudio(entry.recordingId());
            load(entry.id(), true);
        } catch (BackendException e) {
            Notification.show(e.error().message());
        } catch (RuntimeException e) {
            Notification.show(getTranslation("entries.error.unreachable"));
        }
    }

    private void deleteEntry(EntryDto entry) {
        try {
            entries.delete(entry.id());
            UI.getCurrent().navigate(EntriesView.class);
        } catch (BackendException e) {
            Notification.show(e.error().message());
        } catch (RuntimeException e) {
            Notification.show(getTranslation("entries.error.unreachable"));
        }
    }

    private void showMessage(String key) {
        add(new Paragraph(getTranslation(key)));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
