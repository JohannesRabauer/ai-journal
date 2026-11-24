package dev.rabauer.ai.diary.views.today;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.OrderedList;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.client.BackendException;
import dev.rabauer.ai.diary.client.EntriesClient;
import dev.rabauer.ai.diary.client.TranscriptionsClient;
import dev.rabauer.ai.diary.recorder.RecorderComponent;
import dev.rabauer.ai.diary.recorder.RecorderLabels;
import dev.rabauer.ai.diary.shell.DiaryIcons;
import dev.rabauer.ai.diary.shell.MainLayout;
import dev.rabauer.ai.diary.views.entries.AutosaveEditor;
import dev.rabauer.ai.diary.views.entries.AutosavePresenter;
import dev.rabauer.ai.diary.views.entries.BrowserZone;
import dev.rabauer.ai.diary.views.entries.EntryDetailView;
import dev.rabauer.ai.diary.views.entries.EntryEditorPanel;
import dev.rabauer.ai.diary.views.entries.EntryListPresenter;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Today: record or write a new entry, review the transcript, finish. Hard rule (SPEC 4.4): there is no AI output
 * anywhere on this screen; the only background work is transcription of the user's own recording.
 */
@Route(value = "", layout = MainLayout.class)
@CssImport("./today/today.css")
public class TodayView extends Div implements HasDynamicTitle {

    private static final String MODE_KEY = "diary.today.mode";
    private static final String SAVED_CLASS = "today-write--saved";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final EntriesClient entries;
    private final Select<Language> language = new Select<>();
    private final AutosaveEditor writeEditor;
    private final EntryEditorPanel reviewPanel;
    private final Div reviewSection = new Div();
    private final Paragraph eyebrow = new Paragraph();
    private final H1 title = new H1();
    private final Paragraph message = new Paragraph();
    private final OrderedList todayList = new OrderedList();
    private final Paragraph listNote = new Paragraph();
    private final Button speakMode = new Button();
    private final Button writeMode = new Button();
    private final Div speakPanel = new Div();
    private final Div writePanel = new Div();

    public TodayView(EntriesClient entries, TranscriptionsClient transcriptions) {
        this.entries = entries;
        addClassName("today-view");

        eyebrow.addClassNames("diary-eyebrow", "today-eyebrow");
        title.addClassName("today-title");
        title.getElement().setAttribute("tabindex", "-1");
        Paragraph lead = new Paragraph(getTranslation("today.lead"));
        lead.addClassNames("diary-muted", "today-lead");
        refreshHeader();

        language.setLabel(getTranslation("entries.language.label"));
        language.setItems(Language.values());
        language.setItemLabelGenerator(l -> getTranslation("entries.language." + l.name()));
        language.setValue(Language.AUTO);
        language.addThemeName("small");
        language.setPrefixComponent(DiaryIcons.create("globe"));
        language.addClassName("today-lang");

        RecorderComponent recorder = new RecorderComponent(RecorderLabels.from(this::getTranslation));
        recorder.addClassName("today-recorder");
        recorder.addRecordingCompletedListener(e -> onRecordingCompleted(e.entryId()));
        language.addValueChangeListener(e -> recorder.setLanguage(e.getValue()));

        writeEditor = new AutosaveEditor(entries, this::getTranslation, language::getValue,
                AutosavePresenter.forNewEntry(), "", saved -> markSaved(true));
        writeEditor.textArea().setLabel(getTranslation("entries.write.label"));
        writeEditor.textArea().setHelperText(getTranslation("entries.write.helper"));
        Button finishText = new Button(getTranslation("entries.finish"), e -> finishTextEntry());
        finishText.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        finishText.addClassName("today-finish");

        // Segmented control: real buttons with aria-pressed; the panels are toggled server-side.
        configureMode(speakMode, "mic", "today.mode.speak", true);
        configureMode(writeMode, "pen", "today.mode.write", false);
        Div segmented = new Div(speakMode, writeMode);
        segmented.addClassName("today-seg");
        segmented.getElement().setAttribute("role", "group");
        segmented.getElement().setAttribute("aria-label", getTranslation("today.mode.group"));
        Div top = new Div(segmented, language);
        top.addClassName("today-composer-top");

        speakPanel.addClassName("today-speak");
        speakPanel.add(recorder);
        writePanel.addClassName("today-write");
        writePanel.add(writeEditor, finishText);
        writePanel.setVisible(false);
        Div composer = new Div(top, speakPanel, writePanel);
        composer.addClassNames("diary-card", "today-composer");

        reviewPanel = new EntryEditorPanel(entries, transcriptions, this::getTranslation, language::getValue,
                this::onReviewChanged);
        reviewSection.addClassNames("diary-card", "today-review");
        reviewSection.add(new H2(getTranslation("entries.review.title")), reviewPanel);
        reviewSection.setVisible(false);

        message.getElement().setAttribute("role", "status");
        message.addClassName("today-message");
        todayList.addClassName("today-timeline");
        todayList.getElement().setAttribute("role", "list");
        todayList.getElement().setAttribute("aria-label", getTranslation("entries.today.list"));
        listNote.addClassNames("diary-muted", "today-empty");

        H2 earlier = new H2(getTranslation("today.timeline.title"));
        earlier.addClassName("today-earlier");
        add(eyebrow, title, lead, composer, reviewSection, message, earlier, todayList, listNote);
        refreshToday();
    }

    private void configureMode(Button button, String icon, String textKey, boolean pressed) {
        button.setText(getTranslation(textKey));
        button.setIcon(DiaryIcons.create(icon));
        button.addClassName("today-seg-button");
        button.getElement().setAttribute("aria-pressed", String.valueOf(pressed));
        button.addClickListener(e -> setMode(button == writeMode, true));
    }

    private void setMode(boolean write, boolean persist) {
        speakMode.getElement().setAttribute("aria-pressed", String.valueOf(!write));
        writeMode.getElement().setAttribute("aria-pressed", String.valueOf(write));
        speakPanel.setVisible(!write);
        writePanel.setVisible(write);
        if (persist) {
            // best effort: localStorage may be blocked (private window)
            getElement().executeJs("try { localStorage.setItem($0, $1); } catch (e) { }", MODE_KEY,
                    write ? "write" : "speak");
        }
    }

    /** The check icon next to the autosave status is toggled client-side so a failed save never shows it. */
    private void markSaved(boolean saved) {
        writePanel.getElement().executeJs("this.classList.toggle($0, $1)", SAVED_CLASS, saved);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        // Learn the browser time zone so new entries carry the user's local time; refresh "today" once known.
        BrowserZone.init(attachEvent.getUI(), () -> {
            refreshHeader();
            refreshToday();
        });
        // Typing invalidates the "saved" check immediately; the next successful autosave sets it again.
        writePanel.getElement().executeJs(
                "const ta = this.querySelector('vaadin-text-area');"
                        + "if (ta && !this.__todayInput) { this.__todayInput = true;"
                        + " ta.addEventListener('input', () => this.classList.remove($0)); }", SAVED_CLASS);
        // restore the last chosen mode (default: speak)
        attachEvent.getUI().getPage()
                .executeJs("try { return localStorage.getItem($0); } catch (e) { return null; }", MODE_KEY)
                .then(String.class, mode -> {
                    if ("write".equals(mode)) {
                        setMode(true, false);
                    }
                });
    }

    @Override
    public String getPageTitle() {
        return getTranslation("entries.today.title");
    }

    private void refreshHeader() {
        var now = BrowserZone.current().now();
        eyebrow.setText(TodayGreeting.dateLine(now, getLocale()));
        title.setText(getTranslation(TodayGreeting.key(now)));
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
            // the finish button just disappeared: keep keyboard focus on the page instead of losing it to <body>
            title.getElement().executeJs("this.focus()");
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
            markSaved(false);
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
            todayList.setVisible(!today.isEmpty());
            listNote.setVisible(today.isEmpty());
            listNote.setText(getTranslation("entries.today.none"));
            for (EntryDto e : today) {
                todayList.add(timelineItem(e));
            }
        } catch (RuntimeException e) {
            todayList.setVisible(false);
            listNote.setVisible(true);
            listNote.setText(getTranslation("entries.journal.loadFailed"));
        }
    }

    private ListItem timelineItem(EntryDto e) {
        var row = EntryListPresenter.row(e, getLocale());
        Span time = new Span(e.entryTimestamp() == null ? "" : TIME.format(e.entryTimestamp()));
        time.addClassName("today-tl-time");
        Span node = new Span();
        node.addClassName("today-tl-node");
        node.getElement().setAttribute("aria-hidden", "true");

        Div meta = new Div();
        meta.addClassName("today-tl-meta");
        if (e.source() == EntrySource.VOICE) {
            meta.add(chip("diary-chip", "mic", getTranslation("entries.badge.voice")));
        }
        if (e.hasCoachConversation()) {
            meta.add(chip("diary-chip diary-chip-accent", "chat", getTranslation("entries.badge.coach")));
        }
        if (e.status() == EntryStatus.TRANSCRIBING) {
            Span spin = new Span();
            spin.addClassName("today-spin");
            spin.getElement().setAttribute("aria-hidden", "true");
            Span status = new Span(spin, new Span(getTranslation(row.statusKey())));
            status.addClassName("today-tl-status");
            meta.add(status);
        } else if (!row.finished()) {
            Span status = new Span(getTranslation(row.statusKey()));
            status.addClassName("today-tl-status");
            meta.add(status);
        }

        boolean blank = row.preview().isBlank();
        Paragraph excerpt = new Paragraph(blank ? getTranslation("today.entry.noText") : row.preview());
        excerpt.addClassName("today-tl-excerpt");
        if (blank) {
            excerpt.addClassName("diary-muted");
        }
        // Link text = chips + status + excerpt; the time sits just before it in reading order.
        var link = EntryDetailView.link("", e.id());
        link.addClassName("today-tl-link");
        link.removeAll();
        if (meta.getChildren().findAny().isPresent()) {
            link.add(meta);
        }
        link.add(excerpt);
        Div card = new Div(link);
        card.addClassNames("diary-card", "today-tl-card");
        ListItem item = new ListItem(time, node, card);
        item.addClassName("today-tl-item");
        return item;
    }

    private static Span chip(String classes, String icon, String text) {
        Span chip = new Span(DiaryIcons.create(icon), new Span(text));
        chip.addClassNames(classes.split(" "));
        return chip;
    }
}
