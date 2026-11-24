package dev.rabauer.ai.diary.views.memory;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.KeyModifier;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;
import dev.rabauer.ai.diary.api.memory.CandidateStatus;
import dev.rabauer.ai.diary.api.memory.LessonDto;
import dev.rabauer.ai.diary.api.memory.MemoryCandidateDto;
import dev.rabauer.ai.diary.api.memory.MemoryItemDto;
import dev.rabauer.ai.diary.api.memory.MemoryItemType;
import dev.rabauer.ai.diary.api.memory.RevealDto;
import dev.rabauer.ai.diary.api.memory.ReviewCardDto;
import dev.rabauer.ai.diary.api.memory.SourceType;
import dev.rabauer.ai.diary.client.CoachClient;
import dev.rabauer.ai.diary.client.MemoryClient;
import dev.rabauer.ai.diary.shell.MainLayout;
import dev.rabauer.ai.diary.views.coach.CoachView;
import dev.rabauer.ai.diary.views.entries.EntryDetailView;
import dev.rabauer.ai.diary.views.memory.MemoryPresenter.ErrorInfo;
import dev.rabauer.ai.diary.views.memory.MemoryPresenter.Grade;
import dev.rabauer.ai.diary.views.memory.MemoryPresenter.Phase;
import dev.rabauer.ai.diary.views.memory.MemoryPresenter.ReviewSession;
import dev.rabauer.ai.diary.views.memory.MemoryPresenter.Target;

import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * "Remember / Erinnern" (SPEC sections 6 and 12): retrieval practice (prompt first, answer only after an attempt),
 * candidates to approve, edit or discard, the user's cards, and the technique lessons. No scores, percentages or
 * streaks, only "due today: n". Extraction runs only after the user chose "Extract learnings" on an entry or
 * reflection (query parameters {@code source}/{@code id}, removed from the address right away so a reload does not
 * ask the LLM again). Journal content is never logged here.
 */
@Route(value = "memory", layout = MainLayout.class)
@CssImport("./memory/memory.css")
public class MemoryView extends VerticalLayout implements BeforeEnterObserver, HasDynamicTitle {

    public static final String ROUTE = "memory";

    private final MemoryClient memory;
    private final CoachClient coach;

    private final Div status = new Div();
    private final Div errorBox = new Div();
    private final Section dueSection = new Section();
    private final Section candidatesSection = new Section();
    private final Section itemsSection = new Section();
    private final Section lessonsSection = new Section();
    private final TextArea attempt = new TextArea();

    private ReviewSession session = new ReviewSession(List.of());
    private RevealView revealView;
    private List<MemoryCandidateDto> candidates = List.of();
    private List<MemoryItemDto> items = List.of();
    private List<LessonDto> lessons = List.of();
    private Long editing;
    private Long attemptFor;
    private boolean busy;
    private ErrorInfo error;
    private Runnable retry;
    private Target pendingTarget;

    /** The reveal and the entry the source can be opened at (null when unknown or gone). */
    private record RevealView(RevealDto reveal, Long entryId, SourceType sourceType) {
    }

    private record Outcome<T>(T value, RuntimeException failure) {
    }

    public MemoryView(MemoryClient memory, CoachClient coach) {
        this.memory = memory;
        this.coach = coach;
        addClassName("memory-view");
        setMaxWidth("48rem");

        status.addClassName("memory-status");
        status.getElement().setAttribute("role", "status");
        status.getElement().setAttribute("aria-live", "polite");
        errorBox.addClassName("memory-error");
        errorBox.getElement().setAttribute("role", "alert");
        errorBox.setVisible(false);
        for (Section s : List.of(dueSection, candidatesSection, itemsSection, lessonsSection)) {
            s.addClassName("memory-section");
        }
        Paragraph intro = new Paragraph(getTranslation("memory.intro"));
        intro.addClassName("diary-muted");
        add(new H1(getTranslation("memory.title")), intro, status, errorBox, dueSection, candidatesSection, itemsSection, lessonsSection);
    }

    /** "Extract learnings" on an entry or reflection: opens this screen and starts the extraction there. */
    public static void openWithExtraction(UI ui, SourceType type, long sourceId) {
        ui.navigate(ROUTE, QueryParameters.simple(new Target(type, sourceId).toParameters()));
    }

    @Override
    public String getPageTitle() {
        return getTranslation("memory.title");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        pendingTarget = MemoryPresenter.targetFrom(event.getLocation().getQueryParameters().getParameters()).orElse(null);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        loadAll();
        if (pendingTarget != null) {
            Target target = pendingTarget;
            pendingTarget = null;
            attachEvent.getUI().getPage().getHistory().replaceState(null, ROUTE);
            extract(target);
        }
    }

    // ------------------------------------------------------------------ loading and async

    private void loadAll() {
        try {
            session = new ReviewSession(memory.due());
            candidates = memory.candidates(CandidateStatus.PENDING);
            items = memory.items();
            lessons = memory.lessons(getLocale());
            revealView = null;
            error = null;
        } catch (RuntimeException e) {
            error = MemoryPresenter.errorFor(e);
            retry = this::loadAll;
        }
        render();
    }

    private <T> void async(String busyKey, Supplier<T> call, Consumer<T> onOk, Runnable retryAction) {
        UI ui = UI.getCurrent();
        busy = true;
        error = null;
        retry = null;
        if (busyKey != null) {
            status.setText(getTranslation(busyKey));
        }
        render();
        CompletableFuture.supplyAsync(() -> {
            try {
                return new Outcome<T>(call.get(), null);
            } catch (RuntimeException e) {
                return new Outcome<T>(null, e);
            }
        }).thenAccept(outcome -> {
            try {
                ui.access(() -> {
                    busy = false;
                    status.setText("");
                    if (outcome.failure() != null) {
                        error = MemoryPresenter.errorFor(outcome.failure());
                        retry = error.retryable() ? retryAction : null;
                        if (error.refresh()) {
                            refreshAfterConflict();
                            return;
                        }
                        render();
                    } else {
                        onOk.accept(outcome.value());
                    }
                });
            } catch (RuntimeException detached) {
                // the user left the page
            }
        });
    }

    /** A 404/409 means the data changed elsewhere: keep the calm message and reload the lists. */
    private void refreshAfterConflict() {
        ErrorInfo info = error;
        session = new ReviewSession(safe(memory::due, List.of()));
        candidates = safe(() -> memory.candidates(CandidateStatus.PENDING), candidates);
        items = safe(memory::items, items);
        revealView = null;
        editing = null;
        error = info;
        retry = null;
        render();
    }

    private static <T> T safe(Supplier<T> call, T fallback) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private void extract(Target target) {
        async("memory.extract.working", () -> memory.extract(target.type(), target.id()), created -> {
            candidates = safe(() -> memory.candidates(CandidateStatus.PENDING), candidates);
            status.setText(created.isEmpty() ? getTranslation("memory.extract.none")
                    : getTranslation("memory.extract.done", created.size()));
            render();
            if (!created.isEmpty()) {
                candidatesSection.getChildren().findFirst().ifPresent(h -> h.getElement().executeJs("this.focus()"));
            }
        }, () -> extract(target));
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        errorBox.setVisible(error != null);
        errorBox.removeAll();
        if (error != null) {
            errorBox.add(new Paragraph(getTranslation(error.messageKey())));
            if (retry != null) {
                Button again = new Button(getTranslation("memory.retry"), e -> retry.run());
                again.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
                errorBox.add(again);
            }
        }
        renderDue();
        renderCandidates();
        renderItems();
        renderLessons();
    }

    private static H2 heading(String text, String id) {
        H2 h = new H2(text);
        h.setId(id);
        h.getElement().setAttribute("tabindex", "-1");
        return h;
    }

    private static void section(Section section, H2 heading) {
        section.removeAll();
        section.getElement().setAttribute("aria-labelledby", heading.getId().orElse(""));
        section.add(heading);
    }

    private Div labelled(String labelKey, String text) {
        Div d = new Div();
        d.addClassName("memory-labelled");
        Span label = new Span(getTranslation(labelKey));
        label.addClassName("memory-labelled__label");
        Paragraph body = new Paragraph(text);
        body.addClassName("memory-labelled__body");
        d.add(label, body);
        return d;
    }

    private Span typeBadge(MemoryItemType type) {
        Span span = new Span(getTranslation(MemoryPresenter.typeKey(type)));
        span.addClassName("memory-card__type");
        return span;
    }

    // ---- due today / review

    private void renderDue() {
        int due = session.remaining();
        section(dueSection, heading(due == 0 ? getTranslation("memory.due.none") : getTranslation("memory.due.title", due), "memory-due-title"));
        if (session.phase() == Phase.DONE) {
            return;
        }
        ReviewCardDto card = session.current().orElseThrow();
        Div box = new Div();
        box.addClassName("memory-card");
        box.getElement().setAttribute("role", "group");
        box.getElement().setAttribute("aria-labelledby", "memory-card-prompt");
        Paragraph prompt = new Paragraph(card.prompt());
        prompt.setId("memory-card-prompt");
        prompt.addClassName("memory-card__prompt");
        box.add(typeBadge(card.type()), prompt);
        box.add(session.phase() == Phase.PROMPT ? promptControls(card) : revealControls(card));
        dueSection.add(box);
    }

    private Component promptControls(ReviewCardDto card) {
        if (attemptFor == null || attemptFor != card.itemId()) {
            attempt.clear();
            attemptFor = card.itemId();
        }
        attempt.setLabel(getTranslation("memory.review.answerLabel"));
        attempt.setHelperText(getTranslation("memory.review.answerHelper"));
        attempt.setWidthFull();
        attempt.setMinHeight("6rem");
        attempt.setReadOnly(busy);
        Button show = new Button(getTranslation("memory.review.show"),
                e -> submitAttempt(card, MemoryPresenter.attemptText(attempt.getValue())));
        show.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        show.setEnabled(!busy);
        show.addClickShortcut(Key.ENTER, KeyModifier.CONTROL).listenOn(attempt);
        Button inHead = new Button(getTranslation("memory.review.inHead"), e -> submitAttempt(card, null));
        inHead.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        inHead.setEnabled(!busy);
        Div buttons = new Div(show, inHead);
        buttons.addClassName("memory-actions");
        Div wrap = new Div(attempt, buttons);
        wrap.addClassName("memory-attempt");
        if (!busy && error == null) {
            attempt.focus();
        }
        return wrap;
    }

    private void submitAttempt(ReviewCardDto card, String text) {
        if (busy) {
            return;
        }
        async(null, () -> {
            RevealDto reveal = memory.attempt(card.itemId(), text);
            return new RevealView(reveal, resolveEntryId(reveal), reveal.sourceType());
        }, view -> {
            session.revealed(view.reveal());
            attemptFor = null;
            revealView = view;
            render();
        }, () -> submitAttempt(card, text));
    }

    /** The entry to open for the source: the entry itself, or the entry of the reflection; null when unknown. */
    private Long resolveEntryId(RevealDto reveal) {
        if (reveal.sourceType() == null || reveal.sourceId() == null) {
            return null;
        }
        if (reveal.sourceType() == SourceType.ENTRY) {
            return reveal.sourceId();
        }
        try {
            return coach.get(reveal.sourceId()).entryId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Component revealControls(ReviewCardDto card) {
        RevealDto reveal = session.reveal().orElseThrow();
        boolean conversation = revealView != null && revealView.sourceType() == SourceType.CONVERSATION;
        Div wrap = new Div();
        wrap.addClassName("memory-reveal");
        wrap.getElement().setAttribute("role", "region");
        wrap.getElement().setAttribute("aria-label", getTranslation("memory.review.revealLabel"));
        if (reveal.attemptText() != null && !reveal.attemptText().isBlank()) {
            wrap.add(labelled("memory.review.yourAnswer", reveal.attemptText()));
        } else {
            Paragraph head = new Paragraph(getTranslation("memory.review.inHeadNote"));
            head.addClassName("diary-muted");
            wrap.add(head);
        }
        Div answer = labelled("memory.review.answer", reveal.answer());
        answer.addClassName("memory-reveal__answer");
        wrap.add(answer);
        if (reveal.sourceQuote() != null && !reveal.sourceQuote().isBlank()) {
            Div source = labelled(conversation ? "memory.review.sourceConversation" : "memory.review.sourceEntry",
                    "“" + reveal.sourceQuote() + "”");
            source.addClassName("memory-reveal__source");
            Long entryId = revealView == null ? null : revealView.entryId();
            if (entryId != null) {
                source.add(conversation
                        ? new RouterLink(getTranslation("memory.review.openConversation"), CoachView.class,
                                new RouteParameters("id", String.valueOf(entryId)))
                        : EntryDetailView.link(getTranslation("memory.review.openEntry"), entryId));
            }
            wrap.add(source);
        }
        Paragraph label = new Paragraph(getTranslation("memory.review.gradeLabel"));
        label.addClassName("memory-grades__label");
        Div grades = new Div();
        grades.addClassName("memory-grades");
        grades.getElement().setAttribute("role", "group");
        grades.getElement().setAttribute("aria-label", getTranslation("memory.review.gradeLabel"));
        Key[] keys = {Key.DIGIT_1, Key.DIGIT_2, Key.DIGIT_3, Key.DIGIT_4};
        Button first = null;
        for (Grade g : Grade.values()) {
            Button b = new Button(getTranslation(g.key()), e -> grade(card, g));
            b.addThemeVariants(g == Grade.GOOD ? ButtonVariant.LUMO_PRIMARY : ButtonVariant.LUMO_CONTRAST);
            b.setEnabled(!busy);
            b.addClickShortcut(keys[g.ordinal()]);
            grades.add(b);
            if (first == null) {
                first = b;
            }
        }
        Paragraph hint = new Paragraph(getTranslation("memory.review.keysHint"));
        hint.addClassName("diary-muted");
        wrap.add(label, grades, hint);
        if (first != null && !busy) {
            first.focus();
        }
        return wrap;
    }

    private void grade(ReviewCardDto card, Grade grade) {
        if (busy) {
            return;
        }
        async(null, () -> memory.grade(card.itemId(), grade.quality()), item -> {
            session.graded();
            revealView = null;
            items = safe(memory::items, items);
            lessons = safe(() -> memory.lessons(getLocale()), lessons);
            render();
            if (session.phase() == Phase.DONE) {
                dueSection.getChildren().findFirst().ifPresent(h -> h.getElement().executeJs("this.focus()"));
            }
        }, () -> grade(card, grade));
    }

    // ---- candidates

    private void renderCandidates() {
        section(candidatesSection, heading(getTranslation("memory.candidates.title"), "memory-candidates-title"));
        Paragraph intro = new Paragraph(getTranslation(candidates.isEmpty() ? "memory.candidates.none" : "memory.candidates.intro"));
        intro.addClassName("diary-muted");
        candidatesSection.add(intro);
        for (MemoryCandidateDto c : candidates) {
            candidatesSection.add(editing != null && editing == c.id() ? candidateEditor(c) : candidateCard(c));
        }
    }

    private Component candidateCard(MemoryCandidateDto c) {
        Div box = new Div();
        box.addClassName("memory-candidate");
        box.getElement().setAttribute("role", "group");
        String promptId = "memory-candidate-" + c.id();
        box.getElement().setAttribute("aria-labelledby", promptId);
        Paragraph prompt = new Paragraph(c.prompt());
        prompt.setId(promptId);
        prompt.addClassName("memory-card__prompt");
        box.add(typeBadge(c.type()), prompt, labelled("memory.candidate.answer", c.answer()),
                labelled("memory.candidate.quote", "“" + c.sourceQuote() + "”"));
        Button approve = new Button(getTranslation("memory.candidate.approve"), e -> approve(c, null, null, null));
        approve.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button edit = new Button(getTranslation("memory.candidate.edit"), e -> {
            editing = c.id();
            renderCandidates();
        });
        edit.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        Button discard = new Button(getTranslation("memory.candidate.discard"), e -> discard(c));
        discard.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        for (Button b : List.of(approve, edit, discard)) {
            b.setEnabled(!busy);
        }
        Div buttons = new Div(approve, edit, discard);
        buttons.addClassName("memory-actions");
        box.add(buttons);
        return box;
    }

    private Component candidateEditor(MemoryCandidateDto c) {
        Div box = new Div();
        box.addClassName("memory-candidate");
        box.getElement().setAttribute("role", "group");
        box.getElement().setAttribute("aria-label", getTranslation("memory.candidate.editing"));
        TextArea prompt = new TextArea(getTranslation("memory.candidate.prompt"));
        prompt.setValue(c.prompt());
        prompt.setMaxLength(MemoryPresenter.MAX_PROMPT);
        prompt.setWidthFull();
        TextArea answer = new TextArea(getTranslation("memory.candidate.answer"));
        answer.setValue(c.answer());
        answer.setMaxLength(MemoryPresenter.MAX_ANSWER);
        answer.setWidthFull();
        Select<MemoryItemType> type = new Select<>();
        type.setLabel(getTranslation("memory.candidate.type"));
        type.setItems(MemoryItemType.values());
        type.setItemLabelGenerator(t -> getTranslation(MemoryPresenter.typeKey(t)));
        type.setValue(c.type());
        Paragraph problem = new Paragraph();
        problem.addClassName("memory-error-inline");
        problem.getElement().setAttribute("role", "alert");
        Button save = new Button(getTranslation("memory.candidate.save"), e -> {
            var p = MemoryPresenter.editProblem(prompt.getValue(), answer.getValue());
            if (p.isPresent()) {
                problem.setText(getTranslation(p.get()));
                return;
            }
            approve(c, prompt.getValue().strip(), answer.getValue().strip(), type.getValue());
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        save.setEnabled(!busy);
        Button cancel = new Button(getTranslation("memory.cancel"), e -> {
            editing = null;
            renderCandidates();
        });
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        Div buttons = new Div(save, cancel);
        buttons.addClassName("memory-actions");
        box.add(prompt, answer, type, problem, buttons);
        prompt.focus();
        return box;
    }

    private void approve(MemoryCandidateDto c, String prompt, String answer, MemoryItemType type) {
        if (busy) {
            return;
        }
        async(null, () -> memory.approve(c.id(), prompt, answer, type), ok -> {
            editing = null;
            candidates = safe(() -> memory.candidates(CandidateStatus.PENDING), candidates);
            items = safe(memory::items, items);
            lessons = safe(() -> memory.lessons(getLocale()), lessons);
            status.setText(getTranslation("memory.candidate.approved"));
            render();
        }, () -> approve(c, prompt, answer, type));
    }

    private void discard(MemoryCandidateDto c) {
        if (busy) {
            return;
        }
        async(null, () -> memory.discard(c.id()), ok -> {
            candidates = safe(() -> memory.candidates(CandidateStatus.PENDING), candidates);
            status.setText(getTranslation("memory.candidate.discarded"));
            render();
        }, () -> discard(c));
    }

    // ---- items

    private void renderItems() {
        section(itemsSection, heading(getTranslation("memory.items.title"), "memory-items-title"));
        if (items.isEmpty()) {
            Paragraph none = new Paragraph(getTranslation("memory.items.none"));
            none.addClassName("diary-muted");
            itemsSection.add(none);
            return;
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(getLocale());
        Div list = new Div();
        list.getElement().setAttribute("role", "list");
        list.addClassName("memory-items");
        for (MemoryItemDto item : items) {
            Div row = new Div();
            row.addClassName("memory-item");
            row.getElement().setAttribute("role", "listitem");
            Paragraph prompt = new Paragraph(item.prompt());
            prompt.addClassName("memory-card__prompt");
            Span next = new Span(getTranslation("memory.items.next", item.nextReviewOn().format(fmt)));
            next.addClassName("diary-muted");
            Button delete = new Button(getTranslation("memory.items.delete"), e -> confirmDelete(item));
            delete.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
            delete.getElement().setAttribute("aria-label", getTranslation("memory.items.delete.label", item.prompt()));
            delete.setEnabled(!busy);
            row.add(typeBadge(item.type()), prompt, next, delete);
            list.add(row);
        }
        itemsSection.add(list);
    }

    private void confirmDelete(MemoryItemDto item) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader(getTranslation("memory.items.delete.title"));
        dialog.setText(getTranslation("memory.items.delete.text"));
        dialog.setCancelable(true);
        dialog.setCancelText(getTranslation("memory.cancel"));
        dialog.setConfirmText(getTranslation("memory.items.delete"));
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(e -> async(null, () -> {
            memory.deleteItem(item.id());
            return Boolean.TRUE;
        }, ok -> {
            items = safe(memory::items, items);
            session = new ReviewSession(safe(memory::due, List.of()));
            revealView = null;
            status.setText(getTranslation("memory.items.deleted"));
            render();
        }, () -> confirmDelete(item)));
        dialog.open();
    }

    // ---- lessons

    private void renderLessons() {
        section(lessonsSection, heading(getTranslation("memory.lessons.title"), "memory-lessons-title"));
        Paragraph intro = new Paragraph(getTranslation("memory.lessons.intro"));
        intro.addClassName("diary-muted");
        lessonsSection.add(intro);
        for (LessonDto lesson : lessons) {
            if (lesson.unlocked()) {
                Details d = new Details(lesson.title(), new Paragraph(lesson.body()));
                d.addClassName("memory-lesson");
                d.setOpened(false);
                lessonsSection.add(d);
            } else {
                Div locked = new Div();
                locked.addClassNames("memory-lesson", "memory-lesson--locked");
                Span lock = new Span(getTranslation("memory.lesson.locked"));
                lock.addClassName("memory-card__type");
                Paragraph hint = new Paragraph(lesson.unlockHint() == null ? "" : lesson.unlockHint());
                hint.addClassName("diary-muted");
                locked.add(new H3(lesson.title()), lock, hint);
                lessonsSection.add(locked);
            }
        }
    }
}
