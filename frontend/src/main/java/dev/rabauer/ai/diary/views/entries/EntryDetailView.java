package dev.rabauer.ai.diary.views.entries;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
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
import dev.rabauer.ai.diary.shell.DiaryIcons;
import dev.rabauer.ai.diary.shell.MainLayout;
import dev.rabauer.ai.diary.views.EntryFormat;
import dev.rabauer.ai.diary.views.coach.CoachView;
import dev.rabauer.ai.diary.views.memory.MemoryView;

/**
 * Entry detail: text edit with autosave, audio playback through the proxy, delete audio / delete entry (with
 * confirmation), finish. "Reflect" opens the coach for finished entries.
 */
@Route(value = "entries/:id", layout = MainLayout.class)
@CssImport("./entries/entries.css")
public class EntryDetailView extends VerticalLayout implements BeforeEnterObserver, HasDynamicTitle {

    private final EntriesClient entries;
    private final RecordingsClient recordings;
    private final TranscriptionsClient transcriptions;

    public EntryDetailView(EntriesClient entries, RecordingsClient recordings, TranscriptionsClient transcriptions) {
        this.entries = entries;
        this.recordings = recordings;
        this.transcriptions = transcriptions;
        setPadding(false);
        setSpacing(false);
        addClassName("entries-detail");
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
        add(backLink());
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
        boolean finished = entry.status() == EntryStatus.FINISHED;

        java.util.Locale locale = UI.getCurrent().getLocale();
        EntryListPresenter.Row row = EntryListPresenter.row(entry, locale);
        String when = EntryListPresenter.detailEyebrow(entry, locale);
        Paragraph eyebrow = new Paragraph(when);
        eyebrow.addClassName("diary-eyebrow");
        eyebrow.setVisible(!when.isBlank());
        String title = EntryFormat.heading(entry.text());
        H1 heading = new H1(title.isBlank() ? getTranslation("entries.untitled") : title);
        heading.addClassName("entries-title");
        heading.getElement().setAttribute("tabindex", "-1");
        add(eyebrow, heading);
        if (refocus) {
            heading.getElement().executeJs("this.focus()");
        }

        Div chips = new Div();
        chips.addClassName("entries-meta");
        if (entry.source() == EntrySource.VOICE) {
            chips.add(EntriesView.chip(getTranslation("entries.badge.voice"), "mic", false));
        }
        if (entry.hasCoachConversation()) {
            chips.add(EntriesView.chip(getTranslation("entries.badge.coach"), "chat", true));
        }
        if (!finished) {
            Span status = EntriesView.chip(getTranslation(row.statusKey()), null, false);
            status.addClassName("entries-chip-status");
            chips.add(status);
        }
        add(chips);

        if (hasAudio) {
            add(new AudioPlayer(entry.recordingId(), this::getTranslation));
        } else if (recording != null && recording.status() == RecordingStatus.AUDIO_DELETED) {
            Paragraph deleted = new Paragraph(getTranslation("entries.audio.deleted"));
            deleted.addClassName("diary-muted");
            add(deleted);
        }

        EntryEditorPanel panel = new EntryEditorPanel(entries, transcriptions, this::getTranslation,
                () -> Language.AUTO, changed -> load(changed.id(), true));
        panel.getElement().getClassList().add("entries-reading");
        panel.show(entry, hasAudio);
        add(panel);

        if (entry.legacyAiRemark() != null && !entry.legacyAiRemark().isBlank()) {
            Details remark = new Details(getTranslation("entries.legacyRemark"), new Paragraph(entry.legacyAiRemark()));
            remark.setOpened(false);
            add(remark);
        }

        add(reflectCard(entry, finished));

        Div footer = new Div();
        footer.addClassName("entries-actions");
        if (actions.editText()) {
            footer.add(ghost("entries.edit", "pen", e -> panel.focusEditor()));
        }
        if (finished && !blank(entry.text())) {
            Button extract = ghost("memory.extractLearnings", "bulb",
                    e -> MemoryView.openWithExtraction(UI.getCurrent(), SourceType.ENTRY, entry.id()));
            extract.getElement().setAttribute("title", getTranslation("memory.extractLearnings.title"));
            footer.add(extract);
        }
        if (hasAudio && actions.deleteAudio()) {
            footer.add(ghost("entries.audio.delete", "trash",
                    e -> confirm("entries.audio.delete.title", "entries.audio.delete.text",
                            "entries.audio.delete", () -> deleteAudio(entry))));
        }
        if (actions.deleteEntry()) {
            Button delete = ghost("entries.delete", "trash",
                    e -> confirm("entries.delete.title", "entries.delete.text", "entries.delete", () -> deleteEntry(entry)));
            delete.addClassName("entries-danger");
            footer.add(delete);
        }
        add(footer);
    }

    /**
     * Reflect stays on its own route ({@code entries/:id/coach}): the coach owns speech playback, a recorder and
     * recording guards, so it is not embedded as a side panel here. This card makes the entry point prominent.
     */
    private Div reflectCard(EntryDto entry, boolean finished) {
        Div card = new Div();
        card.addClassNames("diary-card", "entries-reflect");
        H2 title = new H2();
        title.addClassName("entries-reflect__title");
        title.add(DiaryIcons.create("chat"), new Span(getTranslation("entries.reflect")));
        Paragraph text = new Paragraph(getTranslation(finished ? "entries.reflect.text" : "coach.notFinished"));
        text.addClassName("diary-muted");
        Button reflect = new Button(
                getTranslation(entry.hasCoachConversation() ? "coach.entry.reflect.open" : "entries.reflect"),
                e -> UI.getCurrent().navigate(CoachView.class, new RouteParameters("id", String.valueOf(entry.id()))));
        reflect.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        reflect.setEnabled(finished);
        card.add(title, text, reflect);
        return card;
    }

    private Button ghost(String key, String icon, ComponentEventListener<ClickEvent<Button>> listener) {
        Button button = new Button(getTranslation(key), listener);
        button.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        button.setIcon(DiaryIcons.create(icon));
        return button;
    }

    private RouterLink backLink() {
        RouterLink link = new RouterLink();
        link.setRoute(EntriesView.class);
        link.addClassName("entries-back");
        link.add(DiaryIcons.create("chevron-left"), new Span(getTranslation("entries.detail.back")));
        return link;
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
