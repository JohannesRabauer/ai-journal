package dev.rabauer.ai.diary.views.gym;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasEnabled;
import com.vaadin.flow.component.HtmlContainer;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.OrderedList;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import dev.rabauer.ai.diary.api.gym.AttemptDto;
import dev.rabauer.ai.diary.api.gym.AttemptRequest;
import dev.rabauer.ai.diary.api.gym.ExerciseDto;
import dev.rabauer.ai.diary.api.gym.GymCategory;
import dev.rabauer.ai.diary.client.GymClient;
import dev.rabauer.ai.diary.client.SystemClient;
import dev.rabauer.ai.diary.shell.DiaryIcons;
import dev.rabauer.ai.diary.shell.MainLayout;
import dev.rabauer.ai.diary.views.entries.BrowserZone;
import dev.rabauer.ai.diary.views.entries.EntryDetailView;
import dev.rabauer.ai.diary.views.gym.GymPresenter.ErrorInfo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Thinking Gym / Denktraining (SPEC sections 7 and 12). Category picker, one exercise at a time with an optional
 * gentle timer, the user's own answer, optional AI follow-up questions (only when asked, only when an LLM is
 * configured) and a history of earlier answers. No scores, no streaks, no counts. Journal content is never logged.
 */
@Route(value = "gym", layout = MainLayout.class)
@CssImport("./gym/gym.css")
public class GymView extends Div implements HasDynamicTitle {

    /** A real blockquote for the journal excerpt. */
    @Tag("blockquote")
    static final class Quote extends HtmlContainer {
    }

    private record Option(GymCategory category, String label) {
    }

    private record Result<T>(T value, RuntimeException failure) {
    }

    private final GymClient gym;
    private final SystemClient system;

    private final Div statusLine = new Div();
    private final Div errorBox = new Div();
    private final Div pickBox = new Div();
    private final Div exerciseBox = new Div();
    private final Div historyBox = new Div();
    private final Div historyList = new Div();
    private final Button historyMore = new Button();
    private final Select<Option> historyFilter = new Select<>();
    private final List<HasEnabled> startButtons = new ArrayList<>();

    private boolean llmAvailable;
    private boolean busy;
    private boolean historyLoaded;
    private int historyShown = GymPresenter.HISTORY_PAGE;
    private List<AttemptDto> history = List.of();
    private Instant shownAt;

    public GymView(GymClient gym, SystemClient system) {
        this.gym = gym;
        this.system = system;
        addClassName("gym-view");

        statusLine.addClassName("gym-status");
        statusLine.getElement().setAttribute("role", "status");
        errorBox.addClassName("gym-error");
        errorBox.getElement().setAttribute("role", "alert");
        errorBox.setVisible(false);

        Paragraph intro = new Paragraph(getTranslation("gym.intro"));
        intro.addClassName("diary-muted");
        buildPicker();
        buildHistory();
        exerciseBox.addClassName("gym-exercise");
        exerciseBox.setVisible(false);
        add(new H1(getTranslation("gym.title")), intro, statusLine, errorBox, pickBox, exerciseBox, historyBox);
    }

    @Override
    public String getPageTitle() {
        return getTranslation("gym.title");
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        BrowserZone.init(attachEvent.getUI(), this::renderHistory);
        // AI follow-up questions are offered only when a real LLM is configured; any problem means "not offered".
        call(system::info, info -> llmAvailable = GymPresenter.llmAvailable(info), e -> llmAvailable = false);
        loadHistory();
    }

    // ------------------------------------------------------------------ picker

    private void buildPicker() {
        pickBox.addClassName("gym-pick");
        Button any = new Button(getTranslation("gym.pick.any"), e -> requestExercise(null));
        any.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        any.setIcon(DiaryIcons.create("sparkle"));
        startButtons.add(any);
        Paragraph help = new Paragraph(getTranslation("gym.pick.anyHelp"));
        help.addClassName("diary-muted");
        Div cards = new Div();
        cards.addClassName("gym-categories");
        cards.getElement().setAttribute("role", "group");
        cards.getElement().setAttribute("aria-label", getTranslation("gym.pick.categoriesLabel"));
        for (GymCategory category : GymCategory.values()) {
            // the whole tile is one real button: name and one-line description are its accessible name
            NativeButton card = new NativeButton();
            card.getElement().setAttribute("type", "button");
            card.addClassNames("gym-card", "diary-card");
            Span name = new Span(getTranslation(GymPresenter.categoryKey(category)));
            name.addClassName("gym-card__title");
            Span description = new Span(getTranslation(GymPresenter.descriptionKey(category)));
            description.addClassName("gym-card__text");
            card.add(name, description);
            card.addClickListener(e -> requestExercise(category));
            startButtons.add(card);
            cards.add(card);
        }
        any.addClassName("gym-surprise");
        pickBox.add(new H2(getTranslation("gym.pick.title")), cards, any, help);
    }

    private void requestExercise(GymCategory category) {
        if (busy) {
            return;
        }
        setBusy("gym.preparing");
        call(() -> gym.next(category, GymPresenter.languageTag(getLocale())), this::showExercise,
                e -> fail(e, () -> requestExercise(category)));
    }

    // ------------------------------------------------------------------ exercise

    private void showExercise(ExerciseDto next) {
        clearBusy();
        shownAt = Instant.now();
        pickBox.setVisible(false);
        historyBox.setVisible(false);
        exerciseBox.removeAll();
        exerciseBox.setVisible(true);

        Button back = new Button(getTranslation("gym.back"), e -> backToPicker());
        back.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        H2 title = new H2(next.title());
        title.getElement().setAttribute("tabindex", "-1");
        Paragraph meta = new Paragraph();
        meta.addClassName("gym-meta");
        meta.add(new Span(getTranslation(GymPresenter.categoryKey(next.category()))));
        meta.add(new Span(getTranslation("gym.meta", next.estimatedMinutes())));
        meta.add(new Span(getTranslation(next.personalized() ? "gym.source.personalized" : "gym.source.template")));

        Paragraph prompt = new Paragraph(next.prompt());
        prompt.addClassNames("gym-prompt", "diary-card");

        exerciseBox.add(back, title, meta, prompt);
        if (next.sourceExcerpt() != null && !next.sourceExcerpt().isBlank()) {
            exerciseBox.add(sourceBlock(next));
        }

        OrderedList steps = new OrderedList();
        steps.addClassName("gym-steps");
        next.steps().forEach(s -> steps.add(new ListItem(s)));
        Div stepsBox = new Div(label("gym.steps.label"), steps);
        stepsBox.getElement().setAttribute("role", "group");
        stepsBox.getElement().setAttribute("aria-label", getTranslation("gym.steps.label"));
        exerciseBox.add(stepsBox, timerBox(), answerBox(next));
        title.getElement().executeJs("this.focus()");
    }

    private Component sourceBlock(ExerciseDto next) {
        Div box = new Div();
        box.addClassName("gym-source");
        box.getElement().setAttribute("role", "group");
        box.getElement().setAttribute("aria-label", getTranslation("gym.source.label"));
        Span label = new Span(getTranslation("gym.source.label"));
        label.addClassName("gym-source__label");
        Quote quote = new Quote();
        quote.setText(next.sourceExcerpt());
        box.add(label, quote);
        if (next.sourceEntryId() != null) {
            box.add(EntryDetailView.link(getTranslation("gym.source.open"), next.sourceEntryId()));
        }
        return box;
    }

    private Component timerBox() {
        Div box = new Div();
        box.addClassName("gym-timer");
        Span value = new Span("0:00");
        value.addClassName("gym-timer__value");
        Span label = new Span(getTranslation("gym.timer.label"));
        Span hint = new Span(getTranslation("gym.timer.hint"));
        hint.addClassName("diary-muted");
        Div running = new Div(label, value, hint);
        running.addClassName("gym-timer__running");
        running.setVisible(false);
        Button toggle = new Button(getTranslation("gym.timer.show"));
        toggle.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        toggle.addClickListener(e -> {
            boolean show = !running.isVisible();
            running.setVisible(show);
            toggle.setText(getTranslation(show ? "gym.timer.hide" : "gym.timer.show"));
            if (show) {
                startClientTimer(value);
            }
        });
        box.add(toggle, running);
        return box;
    }

    /**
     * Counts up in the browser (no server round trips, no live region, so nothing is announced and nothing moves).
     * It starts from the time already spent on this exercise.
     */
    private void startClientTimer(Span value) {
        int already = GymPresenter.durationSeconds(shownAt, Instant.now());
        value.getElement().executeJs("""
                const el = this;
                if (el._timer) { clearInterval(el._timer); }
                const start = Date.now() - $0 * 1000;
                const show = () => {
                  if (!el.isConnected) { clearInterval(el._timer); return; }
                  const s = Math.max(0, Math.floor((Date.now() - start) / 1000));
                  el.textContent = Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
                };
                show();
                el._timer = setInterval(show, 1000);
                """, already);
    }

    private Component answerBox(ExerciseDto next) {
        Div box = new Div();
        box.addClassName("gym-answer");
        TextArea answer = new TextArea(getTranslation("gym.answer.label"));
        answer.setHelperText(getTranslation("gym.answer.helper"));
        answer.setWidthFull();
        answer.setMinHeight("10rem");
        answer.setMaxLength(20_000);
        Button save = new Button(getTranslation("gym.save"));
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button another = new Button(getTranslation("gym.another"), e -> requestExercise(next.category()));
        another.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        Runnable[] saveAction = new Runnable[1];
        saveAction[0] = () -> {
            AttemptRequest request = GymPresenter.request(answer.getValue(),
                    GymPresenter.durationSeconds(shownAt, Instant.now()));
            if (request == null) {
                showError("gym.error.answerEmpty", null);
                answer.focus();
                return;
            }
            if (busy) {
                return;
            }
            setBusy("gym.saving");
            call(() -> gym.attempt(next.id(), request), saved -> {
                clearBusy();
                historyLoaded = false;
                showSaved(next, saved);
            }, err -> fail(err, saveAction[0]));
        };
        save.addClickListener(e -> saveAction[0].run());
        Div actions = new Div(save, another);
        actions.addClassName("gym-actions");
        box.add(answer, actions);
        return box;
    }

    private void showSaved(ExerciseDto done, AttemptDto saved) {
        exerciseBox.removeAll();
        H2 message = new H2(getTranslation("gym.saved"));
        message.getElement().setAttribute("tabindex", "-1");
        Div feedbackBox = new Div();
        feedbackBox.addClassName("gym-feedback");
        feedbackBox.setVisible(false);
        exerciseBox.add(message);
        if (llmAvailable) {
            Paragraph hint = new Paragraph(getTranslation("gym.feedback.hint"));
            hint.addClassName("diary-muted");
            Button ask = new Button(getTranslation("gym.feedback.ask"));
            ask.addClickListener(e -> askForFeedback(saved.id(), ask, feedbackBox));
            exerciseBox.add(hint, ask);
        }
        Button another = new Button(getTranslation("gym.another"), e -> requestExercise(done.category()));
        another.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button back = new Button(getTranslation("gym.back"), e -> backToPicker());
        back.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        Div actions = new Div(another, back);
        actions.addClassName("gym-actions");
        exerciseBox.add(feedbackBox, actions);
        message.getElement().executeJs("this.focus()");
    }

    private void askForFeedback(long attemptId, Button ask, Div feedbackBox) {
        if (busy) {
            return;
        }
        setBusy("gym.feedback.working");
        ask.setEnabled(false);
        call(() -> gym.feedback(attemptId), updated -> {
            clearBusy();
            historyLoaded = false;
            if (!GymPresenter.hasFeedback(updated)) {
                ask.setEnabled(true);
                return;
            }
            ask.setVisible(false);
            feedbackBox.removeAll();
            feedbackBox.setVisible(true);
            H3 title = new H3(getTranslation("gym.feedback.title"));
            title.getElement().setAttribute("tabindex", "-1");
            OrderedList questions = new OrderedList();
            questions.addClassName("gym-questions");
            updated.feedbackQuestions().forEach(q -> questions.add(new ListItem(q)));
            feedbackBox.add(title, questions);
            title.getElement().executeJs("this.focus()");
        }, err -> {
            ask.setEnabled(true);
            fail(err, () -> askForFeedback(attemptId, ask, feedbackBox));
        });
    }

    private void backToPicker() {
        exerciseBox.removeAll();
        exerciseBox.setVisible(false);
        pickBox.setVisible(true);
        historyBox.setVisible(true);
        clearError();
        if (!historyLoaded) {
            loadHistory();
        }
    }

    // ------------------------------------------------------------------ history

    private void buildHistory() {
        historyBox.addClassName("gym-history");
        historyFilter.setLabel(getTranslation("gym.history.filter"));
        List<Option> options = new ArrayList<>();
        options.add(new Option(null, getTranslation("gym.history.all")));
        for (GymCategory c : GymCategory.values()) {
            options.add(new Option(c, getTranslation(GymPresenter.categoryKey(c))));
        }
        historyFilter.setItems(options);
        historyFilter.setItemLabelGenerator(Option::label);
        historyFilter.setValue(options.get(0));
        historyFilter.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                historyShown = GymPresenter.HISTORY_PAGE;
                loadHistory();
            }
        });
        historyMore.setText(getTranslation("gym.history.more"));
        historyMore.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        historyMore.addClickListener(e -> {
            historyShown += GymPresenter.HISTORY_PAGE;
            renderHistory();
        });
        historyMore.setVisible(false);
        historyList.addClassName("gym-history__list");
        historyBox.add(new H2(getTranslation("gym.history.title")), historyFilter, historyList, historyMore);
    }

    private void loadHistory() {
        Option selected = historyFilter.getValue();
        GymCategory category = selected == null ? null : selected.category();
        call(() -> gym.history(category), list -> {
            history = list;
            historyLoaded = true;
            renderHistory();
        }, e -> {
            history = List.of();
            historyList.removeAll();
            historyMore.setVisible(false);
            historyList.add(new Paragraph(getTranslation(GymPresenter.errorFor(e).messageKey())));
        });
    }

    private void renderHistory() {
        historyList.removeAll();
        List<AttemptDto> shown = GymPresenter.visible(history, historyShown);
        if (shown.isEmpty()) {
            Paragraph empty = new Paragraph(getTranslation("gym.history.empty"));
            empty.addClassName("diary-muted");
            historyList.add(empty);
        }
        shown.forEach(a -> historyList.add(historyItem(a)));
        historyMore.setVisible(GymPresenter.hasMore(history, historyShown));
    }

    private Component historyItem(AttemptDto attempt) {
        Span when = new Span(GymPresenter.time(attempt.createdAt(), BrowserZone.current().zone(), getLocale()));
        Span category = new Span(getTranslation(GymPresenter.categoryKey(attempt.category())));
        int minutes = GymPresenter.minutes(attempt.durationSeconds());
        Span duration = new Span(minutes < 1 ? getTranslation("gym.history.under") : getTranslation("gym.history.minutes", minutes));
        Div head = new Div(when, category, duration);
        head.addClassName("gym-history__head");
        Div snippet = new Div(new Span(GymPresenter.snippet(attempt.answer(), 140)));
        snippet.addClassName("gym-history__snippet");
        Details details = new Details(new Div(head, snippet), new Div());
        details.addClassNames("gym-history__item", "diary-card");
        boolean[] filled = {false};
        details.addOpenedChangeListener(e -> {
            if (e.isOpened() && !filled[0]) {
                filled[0] = true;
                fillHistoryDetails(details, attempt);
            }
        });
        return details;
    }

    private void fillHistoryDetails(Details details, AttemptDto attempt) {
        Div body = new Div();
        body.addClassName("gym-history__body");
        Paragraph exerciseLine = new Paragraph(getTranslation("gym.history.loading"));
        exerciseLine.addClassName("diary-muted");
        Paragraph answerLabel = new Paragraph(getTranslation("gym.history.answer"));
        answerLabel.addClassName("gym-label");
        Paragraph answer = new Paragraph(attempt.answer());
        answer.addClassName("gym-answer-text");
        body.add(exerciseLine, answerLabel, answer);
        if (GymPresenter.hasFeedback(attempt)) {
            H3 title = new H3(getTranslation("gym.feedback.title"));
            OrderedList questions = new OrderedList();
            questions.addClassName("gym-questions");
            attempt.feedbackQuestions().forEach(q -> questions.add(new ListItem(q)));
            body.add(title, questions);
        }
        details.setContent(body);
        call(() -> gym.get(attempt.exerciseId()), ex -> {
            exerciseLine.setText(getTranslation("gym.history.exercise") + ": " + ex.title());
            exerciseLine.removeClassName("diary-muted");
        }, e -> exerciseLine.setVisible(false));
    }

    // ------------------------------------------------------------------ plumbing

    private Component label(String key) {
        Span label = new Span(getTranslation(key));
        label.addClassName("gym-label");
        return label;
    }

    private void setBusy(String messageKey) {
        busy = true;
        clearError();
        statusLine.setText(getTranslation(messageKey));
        startButtons.forEach(b -> b.setEnabled(false));
    }

    private void clearBusy() {
        busy = false;
        statusLine.setText("");
        startButtons.forEach(b -> b.setEnabled(true));
    }

    private void fail(RuntimeException failure, Runnable retryAction) {
        clearBusy();
        ErrorInfo info = GymPresenter.errorFor(failure);
        showError(info.messageKey(), info.retryable() ? retryAction : null);
    }

    private void showError(String messageKey, Runnable retryAction) {
        errorBox.removeAll();
        errorBox.add(new Paragraph(getTranslation(messageKey)));
        if (retryAction != null) {
            Button again = new Button(getTranslation("gym.retry"), e -> {
                clearError();
                retryAction.run();
            });
            again.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            errorBox.add(again);
        }
        errorBox.setVisible(true);
    }

    private void clearError() {
        errorBox.removeAll();
        errorBox.setVisible(false);
    }

    /** Runs a backend call off the UI thread and hands the outcome back on it. */
    private <T> void call(Supplier<T> call, Consumer<T> onSuccess, Consumer<RuntimeException> onFailure) {
        UI ui = UI.getCurrent();
        CompletableFuture.supplyAsync(() -> {
            try {
                return new Result<>(call.get(), null);
            } catch (RuntimeException e) {
                return new Result<T>(null, e);
            }
        }).thenAccept(result -> {
            try {
                ui.access(() -> {
                    if (result.failure() != null) {
                        onFailure.accept(result.failure());
                    } else {
                        onSuccess.accept(result.value());
                    }
                });
            } catch (RuntimeException detached) {
                // the user left the page; nothing to update
            }
        });
    }
}
