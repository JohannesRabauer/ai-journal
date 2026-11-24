package dev.rabauer.ai.diary.views.coach;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.KeyModifier;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.OrderedList;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import dev.rabauer.ai.diary.api.coach.AnswerRequest;
import dev.rabauer.ai.diary.api.coach.CoachMessageDto;
import dev.rabauer.ai.diary.api.coach.CoachMessageKind;
import dev.rabauer.ai.diary.api.coach.CoachRole;
import dev.rabauer.ai.diary.api.coach.ConversationDto;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.api.settings.SettingsDto;
import dev.rabauer.ai.diary.client.BackendException;
import dev.rabauer.ai.diary.client.CoachClient;
import dev.rabauer.ai.diary.client.EntriesClient;
import dev.rabauer.ai.diary.client.SettingsClient;
import dev.rabauer.ai.diary.coach.AnswerRecorder;
import dev.rabauer.ai.diary.coach.CoachSpeech;
import dev.rabauer.ai.diary.shell.MainLayout;
import dev.rabauer.ai.diary.views.coach.CoachPresenter.Actions;
import dev.rabauer.ai.diary.views.coach.CoachPresenter.AnswerDraft;
import dev.rabauer.ai.diary.views.coach.CoachPresenter.ErrorInfo;
import dev.rabauer.ai.diary.views.entries.BrowserZone;
import dev.rabauer.ai.diary.views.entries.EntryDetailView;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Coach conversation for an entry (SPEC sections 5, 9, 12). The coach is user-initiated: nothing starts on its own,
 * and new coach messages appear only as the reply to something the user did. While any recording is active the
 * reply is held back and nothing is spoken or announced (the browser side enforces that with a recording guard).
 * Journal content is never logged here.
 */
@Route(value = "entries/:id/coach", layout = MainLayout.class)
@CssImport("./coach/coach.css")
public class CoachView extends VerticalLayout implements BeforeEnterObserver, HasDynamicTitle {

    /** How a freshly shown conversation is presented. */
    private enum Reveal { QUIET, NEW_QUESTIONS, CURRENT_QUESTION }

    private final EntriesClient entries;
    private final CoachClient coach;
    private final SettingsClient settings;

    private final CoachSpeech speech;
    private final AnswerRecorder recorder;
    private final TextArea answer = new TextArea();
    private final Button send = new Button();
    private final AnswerDraft draft = new AnswerDraft();

    private final Div startBox = new Div();
    private final Button startButton = new Button();
    private final Div conversationBox = new Div();
    private final Paragraph statusLine = new Paragraph();
    private final OrderedList transcript = new OrderedList();
    private final Div currentBox = new Div();
    private final Div answerBox = new Div();
    private final Div controls = new Div();
    private final Div thinking = new Div();
    private final Div errorBox = new Div();
    private final Div messageBox = new Div();

    private final Set<Long> shown = new HashSet<>();
    private long entryId;
    private ConversationDto conversation;
    private boolean busy;
    private boolean voiceBusy;
    private String busyKey = "coach.thinking";
    private ErrorInfo error;
    private Runnable retry;
    private ConversationDto held;
    private boolean autoPlay;
    private String voiceDe;
    private String voiceEn;

    public CoachView(EntriesClient entries, CoachClient coach, SettingsClient settings) {
        this.entries = entries;
        this.coach = coach;
        this.settings = settings;
        addClassName("coach-view");
        setMaxWidth("48rem");

        speech = new CoachSpeech(Map.of("stop", getTranslation("coach.speech.stop")));
        recorder = new AnswerRecorder(voiceLabels());
        speech.addRecordingChangedListener(e -> {
            if (!e.recording()) {
                releaseHeldReply();
            }
        });

        buildAnswerBox();
        thinking.addClassName("coach-thinking");
        thinking.getElement().setAttribute("role", "status");
        errorBox.addClassName("coach-error");
        errorBox.getElement().setAttribute("role", "alert");
        statusLine.addClassName("coach-status");
        transcript.addClassName("coach-transcript");
        transcript.getElement().setAttribute("role", "list");
        transcript.getElement().setAttribute("aria-label", getTranslation("coach.transcript.label"));
        currentBox.addClassName("coach-current");
        currentBox.getElement().setAttribute("role", "group");
        controls.addClassName("coach-controls");

        buildStartBox();
        conversationBox.add(statusLine, transcript, currentBox, answerBox, controls);
        add(speech);
        add(messageBox, startBox, thinking, errorBox, conversationBox);
        startBox.setVisible(false);
        conversationBox.setVisible(false);
        thinking.setVisible(false);
        errorBox.setVisible(false);
    }

    public static String path(long entryId) {
        return "entries/" + entryId + "/coach";
    }

    @Override
    public String getPageTitle() {
        return getTranslation("coach.title");
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        BrowserZone.init(attachEvent.getUI(), () -> {
            if (conversation != null) {
                renderTranscript();
            }
        });
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        long id;
        try {
            id = Long.parseLong(event.getRouteParameters().get("id").orElse(""));
        } catch (NumberFormatException e) {
            showMessage("coach.entryNotFound");
            return;
        }
        entryId = id;
        load();
    }

    // ---- loading

    private void load() {
        messageBox.removeAll();
        EntryDto entry;
        try {
            entry = entries.get(entryId);
        } catch (BackendException e) {
            showMessage(e.status() == 404 ? "coach.entryNotFound" : "coach.error.unreachable");
            return;
        } catch (RuntimeException e) {
            showMessage("coach.error.unreachable");
            return;
        }
        if (entry.status() != EntryStatus.FINISHED) {
            showMessage("coach.notFinished");
            return;
        }
        loadSpeechSettings();
        try {
            CoachPresenter.latest(coach.forEntry(entryId)).ifPresent(c -> apply(c, Reveal.QUIET));
        } catch (RuntimeException e) {
            showMessage(CoachPresenter.errorFor(e).messageKey());
            return;
        }
        messageBox.add(backLink());
        refresh();
    }

    private void loadSpeechSettings() {
        try {
            SettingsDto s = settings.get();
            autoPlay = s.ttsAutoPlay() == null || s.ttsAutoPlay();
            voiceDe = s.ttsVoiceDe();
            voiceEn = s.ttsVoiceEn();
        } catch (RuntimeException e) {
            autoPlay = false; // unknown setting: stay silent
        }
    }

    private void showMessage(String key) {
        messageBox.removeAll();
        messageBox.add(backLink(), new Paragraph(getTranslation(key)));
        startBox.setVisible(false);
        conversationBox.setVisible(false);
    }

    private Component backLink() {
        return EntryDetailView.link(getTranslation("coach.back"), entryId);
    }

    // ---- building the persistent parts

    private void buildStartBox() {
        startButton.setText(getTranslation("coach.start.button"));
        startButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        startButton.addClickListener(e -> startConversation());
        startBox.add(new H2(getTranslation("coach.start.title")),
                new Paragraph(getTranslation("coach.start.text")), startButton);
    }

    private void buildAnswerBox() {
        answer.setLabel(getTranslation("coach.answer.label"));
        answer.setHelperText(getTranslation("coach.answer.helper"));
        answer.setId("coach-answer");
        answer.setMinHeight("8rem");
        answer.setWidthFull();
        answer.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                draft.edited(e.getValue());
            }
        });
        send.setText(getTranslation("coach.answer.send"));
        send.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        send.addClickListener(e -> sendAnswer());
        send.addClickShortcut(Key.ENTER, KeyModifier.CONTROL).listenOn(answer);
        recorder.addTranscribedListener(e -> {
            answer.setValue(draft.transcribed(e.text()));
            answer.focus();
        });
        recorder.addBusyListener(e -> {
            voiceBusy = e.busy();
            updateEnabled();
        });
        answerBox.addClassName("coach-answer");
        answerBox.add(answer, recorder, send);
    }

    private Map<String, String> voiceLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String key : List.of("record", "stop", "cancel", "elapsed", "requesting", "recording", "transcribing",
                "done", "cancelled", "error.mic-denied", "error.unsupported", "error.unavailable",
                "error.too-large", "error.empty", "error.failed")) {
            labels.put(key, getTranslation("coach.voice." + key));
        }
        return labels;
    }

    // ---- actions

    private void startConversation() {
        if (busy) {
            return;
        }
        run(() -> coach.start(entryId), Reveal.NEW_QUESTIONS, null, this::startConversation);
    }

    private void sendAnswer() {
        draft.edited(answer.getValue());
        AnswerRequest request = draft.request();
        if (busy || conversation == null || request == null) {
            return;
        }
        long id = conversation.id();
        run(() -> coach.answer(id, request), Reveal.NEW_QUESTIONS, () -> {
            draft.sent();
            answer.clear();
        }, this::sendAnswer);
    }

    private void pause() {
        long id = conversation.id();
        run(() -> coach.pause(id), Reveal.QUIET, null, this::pause);
    }

    private void resume() {
        long id = conversation.id();
        run(() -> coach.resume(id), Reveal.CURRENT_QUESTION, null, this::resume);
    }

    private void end() {
        long id = conversation.id();
        run(() -> coach.end(id), Reveal.QUIET, null, this::end);
    }

    private void confirmEnd() {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader(getTranslation("coach.end.title"));
        dialog.setText(getTranslation("coach.end.text"));
        dialog.setCancelable(true);
        dialog.setCancelText(getTranslation("coach.cancel"));
        dialog.setConfirmText(getTranslation("coach.end.confirm"));
        dialog.addConfirmListener(e -> end());
        dialog.open();
    }

    /** Runs a backend call off the UI thread; the screen shows a calm "thinking" line meanwhile (no animation). */
    private void run(Supplier<ConversationDto> call, Reveal reveal, Runnable onAccepted, Runnable retryAction) {
        UI ui = UI.getCurrent();
        busy = true;
        busyKey = "coach.thinking";
        error = null;
        retry = null;
        refresh();
        CompletableFuture.supplyAsync(() -> {
            try {
                return new Outcome(call.get(), null);
            } catch (RuntimeException e) {
                return new Outcome(null, e);
            }
        }).thenAccept(outcome -> {
            try {
                ui.access(() -> finish(outcome, reveal, onAccepted, retryAction));
            } catch (RuntimeException detached) {
                // the user left the page; nothing to update
            }
        });
    }

    private record Outcome(ConversationDto result, RuntimeException failure) {
    }

    private void finish(Outcome outcome, Reveal reveal, Runnable onAccepted, Runnable retryAction) {
        if (outcome.failure() != null) {
            busy = false;
            ErrorInfo info = CoachPresenter.errorFor(outcome.failure());
            if (info.reload() && conversation != null) {
                reloadAfterConflict(info);
                return;
            }
            error = info;
            retry = info.retryable() ? retryAction : null;
            refresh();
            return;
        }
        ConversationDto result = outcome.result();
        // The coach never posts while the user records: hold the reply until the recording is over.
        speech.isRecording(recording -> {
            if (recording) {
                held = result;
                heldAction = onAccepted;
                heldReveal = reveal;
                busyKey = "coach.waitingForRecording";
                refresh();
            } else {
                accept(result, reveal, onAccepted);
            }
        });
    }

    private Runnable heldAction;
    private Reveal heldReveal;

    private void releaseHeldReply() {
        if (held != null) {
            ConversationDto result = held;
            held = null;
            accept(result, heldReveal, heldAction);
        }
    }

    private void accept(ConversationDto result, Reveal reveal, Runnable onAccepted) {
        busy = false;
        if (onAccepted != null) {
            onAccepted.run();
        }
        apply(result, reveal);
        refresh();
    }

    private void reloadAfterConflict(ErrorInfo info) {
        UI ui = UI.getCurrent();
        long id = conversation.id();
        CompletableFuture.supplyAsync(() -> {
            try {
                return coach.get(id);
            } catch (RuntimeException e) {
                return null;
            }
        }).thenAccept(fresh -> {
            try {
                ui.access(() -> {
                    if (fresh != null) {
                        apply(fresh, Reveal.QUIET);
                    }
                    error = info;
                    refresh();
                });
            } catch (RuntimeException detached) {
                // page closed
            }
        });
    }

    /** Takes a conversation as the current one and presents what is new (spoken, announced, focused). */
    private void apply(ConversationDto next, Reveal reveal) {
        conversation = next;
        recorder.setLanguage(next.language());
        List<CoachMessageDto> toPresent = switch (reveal) {
            case QUIET -> List.of();
            case NEW_QUESTIONS -> CoachPresenter.newQuestions(shown, next);
            case CURRENT_QUESTION -> CoachPresenter.currentQuestion(next).map(List::of).orElse(List.of());
        };
        next.messages().forEach(m -> shown.add(m.id()));
        if (toPresent.isEmpty()) {
            return;
        }
        Language language = CoachPresenter.speechLanguage(next.language(), getLocale());
        if (autoPlay) {
            for (CoachMessageDto question : toPresent) {
                speech.enqueue(question.id(), question.content(), language, voiceFor(language));
            }
        }
        speech.announce(getTranslation("coach.announce.question", toPresent.get(toPresent.size() - 1).content()));
        if (CoachPresenter.actions(next.status()).answer()) {
            speech.focusWhenIdle(answer);
        }
    }

    private String voiceFor(Language language) {
        return CoachPresenter.voiceFor(language, voiceDe, voiceEn);
    }

    // ---- rendering

    private void refresh() {
        boolean hasConversation = conversation != null;
        startBox.setVisible(!hasConversation);
        conversationBox.setVisible(hasConversation);
        thinking.setVisible(busy);
        thinking.setText(busy ? getTranslation(busyKey) : "");
        errorBox.setVisible(error != null);
        errorBox.removeAll();
        if (error != null) {
            errorBox.add(new Paragraph(getTranslation(error.messageKey())));
            if (retry != null) {
                Button again = new Button(getTranslation("coach.retry"), e -> retry.run());
                again.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
                errorBox.add(again);
            }
        }
        startButton.setEnabled(!busy);
        if (!hasConversation) {
            return;
        }
        Actions actions = CoachPresenter.actions(conversation.status());
        statusLine.setText(getTranslation("coach.status." + conversation.status().name()));
        renderTranscript();
        renderCurrent();
        answerBox.setVisible(actions.answer());
        renderControls(actions);
        updateEnabled();
    }

    private void renderTranscript() {
        transcript.removeAll();
        for (CoachMessageDto message : conversation.messages()) {
            transcript.add(messageItem(message));
        }
    }

    private ListItem messageItem(CoachMessageDto message) {
        boolean user = message.role() == CoachRole.USER;
        ListItem item = new ListItem();
        item.addClassNames("coach-message", user ? "coach-message--user" : "coach-message--coach");
        if (message.kind() == CoachMessageKind.EXPLANATION) {
            item.addClassName("coach-message--explanation");
        }
        item.getElement().setAttribute("role", "listitem");

        Div head = new Div();
        head.addClassName("coach-message__head");
        Span speaker = new Span(getTranslation("coach.speaker." + message.role().name()));
        speaker.addClassName("coach-message__speaker");
        head.add(speaker, new Span(getTranslation(CoachPresenter.kindKey(message.kind()))));
        if (user && message.voice()) {
            head.add(new Span(getTranslation("coach.voiceBadge")));
        }
        Time time = new Time(message.createdAt(),
                CoachPresenter.time(message.createdAt(), BrowserZone.current().zone(), getLocale()));
        head.add(time);

        Paragraph body = new Paragraph(message.content());
        body.addClassName("coach-message__body");
        item.add(head, body);

        if (!user) {
            Language language = CoachPresenter.speechLanguage(conversation.language(), getLocale());
            Button play = new Button(getTranslation("coach.play"),
                    e -> speech.play(message.id(), message.content(), language, voiceFor(language)));
            play.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            play.getElement().setAttribute("aria-label", getTranslation("coach.play.label"));
            Div actions = new Div(play);
            actions.addClassName("coach-message__actions");
            item.add(actions);
        }
        return item;
    }

    private void renderCurrent() {
        currentBox.removeAll();
        var question = CoachPresenter.currentQuestion(conversation);
        currentBox.setVisible(question.isPresent());
        question.ifPresent(q -> {
            H3 title = new H3(getTranslation("coach.current.title"));
            title.setId("coach-current-title");
            currentBox.getElement().setAttribute("aria-labelledby", "coach-current-title");
            currentBox.add(title, new Paragraph(q.content()));
        });
    }

    private void renderControls(Actions actions) {
        controls.removeAll();
        if (actions.pause()) {
            controls.add(secondary("coach.pause", e -> pause()));
        }
        if (actions.resume()) {
            Button resume = new Button(getTranslation("coach.resume"), e -> resume());
            resume.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            controls.add(resume);
        }
        if (actions.end()) {
            controls.add(secondary("coach.end", e -> confirmEnd()));
        }
        if (actions.startNew()) {
            Button again = new Button(getTranslation("coach.startNew.button"), e -> startNew());
            again.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            controls.add(again);
        }
    }

    private Button secondary(String key, com.vaadin.flow.component.ComponentEventListener<com.vaadin.flow.component.ClickEvent<Button>> listener) {
        Button button = new Button(getTranslation(key), listener);
        button.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        return button;
    }

    /** "Start a new reflection" after an ended one: back to the calm start state, nothing runs until the user starts. */
    private void startNew() {
        conversation = null;
        error = null;
        shown.clear();
        draft.sent();
        answer.clear();
        refresh();
        startButton.focus();
    }

    /** Pause and end must not pull the page away from a running voice recording; send is held while busy. */
    private void updateEnabled() {
        answer.setReadOnly(busy);
        send.setEnabled(!busy && !voiceBusy);
        for (Component control : controls.getChildren().toList()) {
            if (control instanceof Button b) {
                b.setEnabled(!busy && !voiceBusy);
            }
        }
    }

    /** {@code <time datetime="...">} so assistive technology gets the machine-readable value. */
    @Tag("time")
    private static final class Time extends Component {
        Time(Instant instant, String text) {
            getElement().setText(text);
            if (instant != null) {
                getElement().setAttribute("datetime", instant.toString());
            }
        }
    }
}
