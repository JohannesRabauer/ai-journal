package dev.rabauer.ai.diary.views.weekly;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasStyle;
import com.vaadin.flow.component.HasText;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.insights.WeeklyReportDto;
import dev.rabauer.ai.diary.api.insights.WeeklyReportSummaryDto;
import dev.rabauer.ai.diary.client.EntriesClient;
import dev.rabauer.ai.diary.client.WeeklyClient;
import dev.rabauer.ai.diary.shell.MainLayout;
import dev.rabauer.ai.diary.views.entries.BrowserZone;
import dev.rabauer.ai.diary.views.entries.EntryDetailView;
import dev.rabauer.ai.diary.views.weekly.WeeklyPresenter.EditDraft;
import dev.rabauer.ai.diary.views.weekly.WeeklyPresenter.ErrorInfo;
import dev.rabauer.ai.diary.views.weekly.WeeklyPresenter.Week;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Weekly reflection (SPEC sections 8 and 12): pick a week, create the reflection on request, read its six sections,
 * edit, create again, delete, and browse past weeks. Nothing is created automatically, and nothing here scores,
 * counts or grades. Journal content is never logged.
 */
@Route(value = "weekly", layout = MainLayout.class)
@CssImport("./weekly/weekly.css")
public class WeeklyView extends VerticalLayout implements HasDynamicTitle {

    /** A report with the dates of the entries it links to (for readable link texts). */
    private record Loaded(WeeklyReportDto report, Map<Long, String> entryDates) {
    }

    private record Outcome<T>(T result, RuntimeException failure) {
    }

    /** A semantic {@code <blockquote>} for the user's own words. */
    @Tag("blockquote")
    private static final class Quote extends Component implements HasText, HasStyle {
    }

    private final WeeklyClient weekly;
    private final EntriesClient entries;

    private final Select<Week> picker = new Select<>();
    private final Div statusLine = new Div();
    private final Div errorBox = new Div();
    private final Div notice = new Div();
    private final Div reportBox = new Div();
    private final Div historyBox = new Div();

    private List<WeeklyReportSummaryDto> summaries = List.of();
    private Week selected;
    private Loaded current;
    private EditDraft draft;
    private boolean busy;
    private boolean updatingPicker;
    private String busyKey = "weekly.creating";
    private ErrorInfo error;
    private Runnable retry;
    private Component focusTarget;

    public WeeklyView(WeeklyClient weekly, EntriesClient entries) {
        this.weekly = weekly;
        this.entries = entries;
        addClassName("weekly-view");
        setMaxWidth("48rem");

        statusLine.addClassName("weekly-status");
        statusLine.getElement().setAttribute("role", "status");
        errorBox.addClassName("weekly-error");
        errorBox.getElement().setAttribute("role", "alert");
        notice.addClassName("weekly-notice");
        notice.getElement().setAttribute("role", "status");
        reportBox.addClassName("weekly-report");
        historyBox.addClassName("weekly-history");

        picker.setLabel(getTranslation("weekly.picker.label"));
        picker.setWidthFull();
        picker.addValueChangeListener(e -> {
            if (!updatingPicker && e.getValue() != null && !e.getValue().equals(e.getOldValue())) {
                select(e.getValue());
            }
        });

        Paragraph intro = new Paragraph(getTranslation("weekly.intro"));
        intro.addClassName("weekly-muted");
        add(new H1(getTranslation("weekly.title")), intro, picker, statusLine, errorBox, notice, reportBox, historyBox);
        statusLine.setVisible(false);
        errorBox.setVisible(false);
        notice.setVisible(false);
    }

    @Override
    public String getPageTitle() {
        return getTranslation("weekly.title");
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        // "today" depends on the browser zone; refresh the picker once it is known (immediately if cached)
        BrowserZone.init(attachEvent.getUI(), this::zoneKnown);
        loadSummaries();
        Week start = selected != null ? selected : WeeklyPresenter.lastCompleted(today());
        rebuildPicker(start);
        select(start);
    }

    private LocalDate today() {
        return BrowserZone.current().today();
    }

    private void zoneKnown() {
        if (selected != null && !busy && draft == null) {
            rebuildPicker(selected);
        }
    }

    // ------------------------------------------------------------------ data

    private void loadSummaries() {
        try {
            summaries = weekly.list();
        } catch (RuntimeException e) {
            summaries = List.of();
            showError(WeeklyPresenter.errorFor(e), null);
        }
    }

    private void rebuildPicker(Week keep) {
        LocalDate today = today();
        List<Week> weeks = WeeklyPresenter.pickerWeeks(today, summaries.stream().map(WeeklyPresenter::weekOf).toList());
        Week thisWeek = WeeklyPresenter.thisWeek(today);
        Week last = WeeklyPresenter.lastCompleted(today);
        updatingPicker = true;
        try {
            picker.setItems(weeks);
            picker.setItemLabelGenerator(w -> {
                String key = w.equals(thisWeek) ? "weekly.week.thisSoFar" : w.equals(last) ? "weekly.week.last" : "weekly.week.option";
                return getTranslation(key, String.valueOf(w.isoWeek()), WeeklyPresenter.range(w, getLocale()));
            });
            picker.setValue(keep != null && weeks.contains(keep) ? keep : last);
        } finally {
            updatingPicker = false;
        }
    }

    /** Shows the stored report of a week, or the offer to create one. */
    private void select(Week week) {
        selected = week;
        draft = null;
        clearMessages();
        Optional<WeeklyReportSummaryDto> summary = WeeklyPresenter.summaryOf(week, summaries);
        if (summary.isEmpty()) {
            current = null;
            render();
            return;
        }
        long id = summary.get().id();
        current = null;
        run("weekly.loading", () -> load(id), loaded -> {
            current = loaded;
            render();
        }, () -> select(week));
    }

    private Loaded load(long id) {
        WeeklyReportDto report = weekly.get(id);
        Map<Long, String> dates = new HashMap<>();
        for (Long entryId : WeeklyPresenter.referencedEntries(report)) {
            try {
                EntryDto entry = entries.get(entryId);
                String date = WeeklyPresenter.entryDate(entry.entryTimestamp(), localeOrEnglish());
                if (date != null) {
                    dates.put(entryId, date);
                }
            } catch (RuntimeException e) {
                // the entry may be gone; its link then shows the generic text
            }
        }
        return new Loaded(report, dates);
    }

    private Locale localeOrEnglish() {
        Locale locale = UI.getCurrent() == null ? null : UI.getCurrent().getLocale();
        return locale == null ? Locale.ENGLISH : locale;
    }

    // ------------------------------------------------------------------ actions

    private void create() {
        Week week = selected;
        if (week == null || busy) {
            return;
        }
        run("weekly.creating", () -> weekly.generate(week.isoYear(), week.isoWeek()), report -> {
            loadSummaries();
            rebuildPicker(week);
            // links need the entry dates: load them like for a stored report
            run("weekly.loading", () -> load(report.id()), loaded -> {
                current = loaded;
                notice("weekly.announce.created");
                render();
                if (focusTarget != null) {
                    focusTarget.getElement().callJsFunction("focus");
                }
            }, null);
        }, this::create);
    }

    private void confirmRegenerate() {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader(getTranslation("weekly.regenerate.title"));
        dialog.setText(getTranslation("weekly.regenerate.text"));
        dialog.setCancelable(true);
        dialog.setCancelText(getTranslation("weekly.cancel"));
        dialog.setConfirmText(getTranslation("weekly.regenerate.confirm"));
        dialog.addConfirmListener(e -> create());
        dialog.open();
    }

    private void confirmDelete() {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader(getTranslation("weekly.delete.title"));
        dialog.setText(getTranslation("weekly.delete.text"));
        dialog.setCancelable(true);
        dialog.setCancelText(getTranslation("weekly.cancel"));
        dialog.setConfirmText(getTranslation("weekly.delete.confirm"));
        dialog.addConfirmListener(e -> delete());
        dialog.open();
    }

    private void delete() {
        if (current == null || busy) {
            return;
        }
        long id = current.report().id();
        Week week = selected;
        run("weekly.deleting", () -> {
            weekly.delete(id);
            return Boolean.TRUE;
        }, done -> {
            current = null;
            draft = null;
            loadSummaries();
            rebuildPicker(week);
            notice("weekly.announce.deleted");
        }, this::delete);
    }

    private void startEdit() {
        if (current == null || busy) {
            return;
        }
        clearMessages();
        draft = new EditDraft(current.report());
        render();
    }

    private void cancelEdit() {
        draft = null;
        render();
    }

    private void save() {
        if (draft == null || busy) {
            return;
        }
        WeeklyReportDto edited = draft.toReport();
        Map<Long, String> dates = current.entryDates();
        run("weekly.saving", () -> weekly.update(edited.id(), edited), saved -> {
            current = new Loaded(saved, dates);
            draft = null;
            notice("weekly.announce.saved");
        }, this::save);
    }

    /** Runs a backend call off the UI thread; the screen shows a calm status line meanwhile (no animation). */
    private <T> void run(String busyText, Supplier<T> call, Consumer<T> onSuccess, Runnable retryAction) {
        UI ui = UI.getCurrent();
        busy = true;
        busyKey = busyText;
        clearMessages();
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
                    if (outcome.failure() != null) {
                        ErrorInfo info = WeeklyPresenter.errorFor(outcome.failure());
                        if (info.reload()) {
                            loadSummaries();
                            rebuildPicker(selected);
                            current = null;
                            draft = null;
                        }
                        showError(info, info.retryable() ? retryAction : null);
                        render();
                        return;
                    }
                    onSuccess.accept(outcome.result());
                    if (!busy) {
                        render();
                    }
                });
            } catch (RuntimeException detached) {
                // the user left the page; nothing to update
            }
        });
    }

    private void notice(String key) {
        notice.setText(getTranslation(key));
        notice.setVisible(true);
    }

    private void clearMessages() {
        error = null;
        retry = null;
        notice.setVisible(false);
        notice.setText("");
    }

    private void showError(ErrorInfo info, Runnable retryAction) {
        error = info;
        retry = retryAction;
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        statusLine.setVisible(busy);
        statusLine.setText(busy ? getTranslation(busyKey) : "");
        errorBox.setVisible(error != null);
        errorBox.removeAll();
        if (error != null) {
            errorBox.add(new Paragraph(getTranslation(error.messageKey())));
            if (retry != null) {
                Button again = new Button(getTranslation("weekly.retry"), e -> retry.run());
                again.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
                errorBox.add(again);
            }
        }
        picker.setEnabled(!busy && draft == null);

        reportBox.removeAll();
        focusTarget = null;
        if (selected != null && !(busy && current == null)) {
            if (current == null) {
                reportBox.add(emptyWeek());
            } else if (draft != null) {
                reportBox.add(editor());
            } else {
                reportBox.add(reportView());
            }
        }
        renderHistory();
    }

    private Component emptyWeek() {
        Div box = new Div();
        box.addClassName("weekly-empty");
        Paragraph text = new Paragraph(getTranslation("weekly.empty.text"));
        Button create = new Button(getTranslation("weekly.create"), e -> create());
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        create.setEnabled(!busy);
        box.add(text, create);
        return box;
    }

    private Component reportView() {
        WeeklyReportDto r = current.report();
        Div box = new Div();

        H2 heading = new H2(getTranslation("weekly.week.heading", String.valueOf(r.isoWeek())));
        heading.getElement().setAttribute("tabindex", "-1");
        focusTarget = heading;
        Paragraph dates = new Paragraph(WeeklyPresenter.range(WeeklyPresenter.weekOf(r), getLocale()));
        dates.addClassName("weekly-muted");
        Paragraph meta = new Paragraph(metaText(r));
        meta.addClassName("weekly-muted");
        box.add(heading, dates, meta, actions());

        // 1. Recurring themes
        Section themes = section("weekly.section.themes");
        if (r.recurringThemes().isEmpty()) {
            themes.add(none());
        }
        for (WeeklyReportDto.Theme t : r.recurringThemes()) {
            Div theme = new Div();
            theme.addClassName("weekly-theme");
            Span title = new Span(t.title());
            title.addClassName("weekly-theme__title");
            Paragraph description = new Paragraph(t.description());
            theme.add(title, description);
            if (t.entryIds() != null && !t.entryIds().isEmpty()) {
                Div links = new Div(new Span(getTranslation("weekly.entries.label") + ":"));
                links.addClassName("weekly-links");
                t.entryIds().forEach(id -> links.add(entryLink(id)));
                theme.add(links);
            }
            themes.add(theme);
        }
        box.add(themes);

        // 2. In your own words
        Section quotes = section("weekly.section.quotes");
        if (r.inYourOwnWords().isEmpty()) {
            quotes.add(none());
        }
        for (WeeklyReportDto.Quote q : r.inYourOwnWords()) {
            Quote quote = new Quote();
            quote.setText(q.text());
            quote.addClassName("weekly-quote");
            Div source = new Div(entryLink(q.entryId()));
            source.addClassName("weekly-quote__source");
            quotes.add(quote, source);
        }
        box.add(quotes);

        // 3. Open threads
        box.add(listSection("weekly.section.threads", r.openThreads()));

        // 4. What you learned: plain counts, no grading
        Section learned = section("weekly.section.learned");
        WeeklyReportDto.Learned l = r.whatYouLearned();
        if (WeeklyPresenter.hasLearned(l)) {
            learned.add(new Paragraph(getTranslation("weekly.learned.summary", l.itemsCreated(), l.reviewsDone())));
            if (l.itemPrompts() != null && !l.itemPrompts().isEmpty()) {
                learned.add(new Span(getTranslation("weekly.learned.prompts") + ":"), bullets(l.itemPrompts()));
            }
        } else {
            learned.add(new Paragraph(getTranslation("weekly.learned.none")));
        }
        box.add(learned);

        // 5. Noticed patterns
        box.add(listSection("weekly.section.patterns", r.noticedPatterns()));

        // 6. A question for next week
        Section question = section("weekly.section.question");
        if (r.questionForNextWeek() == null || r.questionForNextWeek().isBlank()) {
            question.add(none());
        } else {
            Paragraph q = new Paragraph(r.questionForNextWeek());
            q.addClassName("weekly-question");
            question.add(q);
        }
        box.add(question);
        return box;
    }

    private String metaText(WeeklyReportDto r) {
        var zone = BrowserZone.current().zone();
        String text = getTranslation("weekly.generated", WeeklyPresenter.dateTime(r.generatedAt(), zone, getLocale()));
        if (r.editedAt() != null) {
            text += " · " + getTranslation("weekly.edited", WeeklyPresenter.dateTime(r.editedAt(), zone, getLocale()));
        }
        return text;
    }

    private Component actions() {
        Div actions = new Div();
        actions.addClassName("weekly-actions");
        Button edit = new Button(getTranslation("weekly.edit"), e -> startEdit());
        edit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button again = new Button(getTranslation("weekly.regenerate"), e -> confirmRegenerate());
        again.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        Button delete = new Button(getTranslation("weekly.delete"), e -> confirmDelete());
        delete.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        edit.setEnabled(!busy);
        again.setEnabled(!busy);
        delete.setEnabled(!busy);
        actions.add(edit, again, delete);
        return actions;
    }

    private Section section(String titleKey) {
        Section section = new Section();
        section.addClassName("weekly-section");
        H3 title = new H3(getTranslation(titleKey));
        String id = titleKey.replace('.', '-');
        title.setId(id);
        section.getElement().setAttribute("aria-labelledby", id);
        section.add(title);
        return section;
    }

    private Section listSection(String titleKey, List<String> items) {
        Section section = section(titleKey);
        if (items == null || items.isEmpty()) {
            section.add(none());
        } else {
            section.add(bullets(items));
        }
        return section;
    }

    private Paragraph none() {
        Paragraph p = new Paragraph(getTranslation("weekly.section.none"));
        p.addClassName("weekly-muted");
        return p;
    }

    private UnorderedList bullets(List<String> items) {
        UnorderedList list = new UnorderedList();
        list.addClassName("weekly-list");
        items.forEach(s -> list.add(new ListItem(s)));
        return list;
    }

    private Component entryLink(long entryId) {
        String date = current == null ? null : current.entryDates().get(entryId);
        String text = date == null ? getTranslation("weekly.entry.linkFallback") : getTranslation("weekly.entry.link", date);
        var link = EntryDetailView.link(text, entryId);
        link.addClassName("weekly-entry-link");
        return link;
    }

    // ------------------------------------------------------------------ editor

    private Component editor() {
        WeeklyReportDto r = current.report();
        Div box = new Div();
        box.addClassName("weekly-editor");
        H2 heading = new H2(getTranslation("weekly.week.heading", String.valueOf(r.isoWeek())));
        Paragraph help = new Paragraph(getTranslation("weekly.edit.help"));
        help.addClassName("weekly-muted");
        box.add(heading, help);

        Section themes = section("weekly.section.themes");
        for (EditDraft.ThemeRow row : draft.themes) {
            Div item = new Div();
            item.addClassName("weekly-edit-item");
            TextField title = new TextField(getTranslation("weekly.edit.theme.title"));
            title.setWidthFull();
            title.setValue(nz(row.title));
            title.addValueChangeListener(e -> row.title = e.getValue());
            TextArea description = new TextArea(getTranslation("weekly.edit.theme.description"));
            description.setWidthFull();
            description.setValue(nz(row.description));
            description.addValueChangeListener(e -> row.description = e.getValue());
            item.add(title, description, removeButton(row.title, () -> draft.themes.remove(row)));
            themes.add(item);
        }
        Button addTheme = new Button(getTranslation("weekly.edit.addTheme"), e -> {
            draft.addTheme();
            render();
        });
        addTheme.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        themes.add(addTheme);
        box.add(themes);

        Section quotes = section("weekly.section.quotes");
        for (EditDraft.QuoteRow row : draft.quotes) {
            Div item = new Div();
            item.addClassName("weekly-edit-item");
            TextArea text = new TextArea(getTranslation("weekly.edit.quote"));
            text.setWidthFull();
            text.setValue(nz(row.text));
            text.addValueChangeListener(e -> row.text = e.getValue());
            item.add(text, removeButton(row.text, () -> draft.quotes.remove(row)));
            quotes.add(item);
        }
        box.add(quotes);

        box.add(stringListEditor("weekly.section.threads", "weekly.edit.thread", "weekly.edit.addThread", draft.openThreads));

        Section learned = section("weekly.section.learned");
        IntegerField items = counter("weekly.edit.itemsCreated", draft.itemsCreated, v -> draft.itemsCreated = v);
        IntegerField reviews = counter("weekly.edit.reviewsDone", draft.reviewsDone, v -> draft.reviewsDone = v);
        Div counters = new Div(items, reviews);
        counters.addClassName("weekly-counters");
        learned.add(counters);
        box.add(learned);

        box.add(stringListEditor("weekly.section.patterns", "weekly.edit.pattern", "weekly.edit.addPattern", draft.patterns));

        Section question = section("weekly.section.question");
        TextArea q = new TextArea(getTranslation("weekly.edit.question"));
        q.setWidthFull();
        q.setValue(nz(draft.question));
        q.addValueChangeListener(e -> draft.question = e.getValue());
        question.add(q);
        box.add(question);

        Button save = new Button(getTranslation("weekly.save"), e -> save());
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button(getTranslation("weekly.cancel"), e -> cancelEdit());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        Div actions = new Div(save, cancel);
        actions.addClassName("weekly-actions");
        box.add(actions);
        return box;
    }

    private Section stringListEditor(String titleKey, String labelKey, String addKey, List<String> values) {
        Section section = section(titleKey);
        for (int i = 0; i < values.size(); i++) {
            int index = i;
            Div item = new Div();
            item.addClassName("weekly-edit-item");
            TextField field = new TextField(getTranslation(labelKey));
            field.setWidthFull();
            field.setValue(nz(values.get(i)));
            field.addValueChangeListener(e -> values.set(index, e.getValue()));
            item.add(field, removeButton(values.get(i), () -> values.remove(index)));
            section.add(item);
        }
        Button add = new Button(getTranslation(addKey), e -> {
            values.add("");
            render();
        });
        add.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        section.add(add);
        return section;
    }

    private IntegerField counter(String labelKey, int value, IntConsumer onChange) {
        IntegerField field = new IntegerField(getTranslation(labelKey));
        field.setMin(0);
        field.setStepButtonsVisible(true);
        field.setValue(value);
        field.addValueChangeListener(e -> onChange.accept(e.getValue() == null ? 0 : Math.max(0, e.getValue())));
        return field;
    }

    private Button removeButton(String what, Runnable removal) {
        Button remove = new Button(getTranslation("weekly.edit.remove"), e -> {
            removal.run();
            render();
        });
        remove.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        String label = what == null ? "" : what.strip();
        if (label.length() > 40) {
            label = label.substring(0, 40) + "…";
        }
        remove.getElement().setAttribute("aria-label", getTranslation("weekly.edit.removeLabel", label));
        return remove;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------ history

    private void renderHistory() {
        historyBox.removeAll();
        Section section = new Section();
        section.addClassName("weekly-section");
        H2 title = new H2(getTranslation("weekly.history.title"));
        title.setId("weekly-history-title");
        section.getElement().setAttribute("aria-labelledby", "weekly-history-title");
        section.add(title);
        if (summaries.isEmpty()) {
            Paragraph none = new Paragraph(getTranslation("weekly.history.empty"));
            none.addClassName("weekly-muted");
            section.add(none);
        } else {
            UnorderedList list = new UnorderedList();
            list.addClassName("weekly-history-list");
            var zone = BrowserZone.current().zone();
            for (WeeklyReportSummaryDto s : summaries) {
                Week week = WeeklyPresenter.weekOf(s);
                Button open = new Button(getTranslation("weekly.history.item", String.valueOf(s.isoWeek()), String.valueOf(s.isoYear())),
                        e -> chooseFromHistory(week));
                open.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
                open.setEnabled(!busy && draft == null);
                if (week.equals(selected)) {
                    open.getElement().setAttribute("aria-current", "true");
                }
                Span meta = new Span(WeeklyPresenter.range(week, getLocale()) + " · "
                        + getTranslation("weekly.history.created", WeeklyPresenter.dateTime(s.generatedAt(), zone, getLocale())));
                meta.addClassName("weekly-muted");
                list.add(new ListItem(open, meta));
            }
            section.add(list);
        }
        historyBox.add(section);
    }

    private void chooseFromHistory(Week week) {
        rebuildPicker(week);
        select(week);
    }
}
