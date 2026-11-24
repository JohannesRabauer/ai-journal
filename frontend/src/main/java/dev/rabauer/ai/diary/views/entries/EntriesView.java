package dev.rabauer.ai.diary.views.entries;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;
import dev.rabauer.ai.diary.api.common.PageDto;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.client.EntriesClient;
import dev.rabauer.ai.diary.shell.DiaryIcons;
import dev.rabauer.ai.diary.shell.MainLayout;
import dev.rabauer.ai.diary.views.entries.EntryListPresenter.DayGroup;
import dev.rabauer.ai.diary.views.entries.EntryListPresenter.DayHeader;
import dev.rabauer.ai.diary.views.entries.EntryListPresenter.Row;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Journal: entries grouped by local day, newest first. Older entries are appended with "Load older entries" (no
 * page numbers). No AI output; legacy AI remarks stay collapsed. The backend offers no search or filter parameters,
 * so none is shown here.
 */
@Route(value = "entries", layout = MainLayout.class)
@CssImport("./entries/entries.css")
public class EntriesView extends VerticalLayout implements HasDynamicTitle {

    static final int PAGE_SIZE = 20;

    private final EntriesClient entries;
    private final Paragraph eyebrow = new Paragraph();
    private final Div days = new Div();
    private final Paragraph message = new Paragraph();
    private final Paragraph progress = new Paragraph();
    private final Button loadOlder = new Button();

    private List<EntryDto> loaded = new ArrayList<>();
    private int nextPage;
    private long total;
    private RouterLink focusTarget;

    public EntriesView(EntriesClient entries) {
        this.entries = entries;
        setPadding(false);
        setSpacing(false);
        addClassName("entries-journal");

        eyebrow.addClassName("diary-eyebrow");
        eyebrow.setVisible(false);
        days.addClassName("entries-days");
        message.addClassName("diary-muted");
        message.getElement().setAttribute("role", "status");
        message.setVisible(false);
        progress.addClassNames("entries-progress", "diary-muted");
        progress.getElement().setAttribute("role", "status");
        loadOlder.setText(getTranslation("entries.journal.loadOlder"));
        loadOlder.addClickListener(e -> loadNext());
        loadOlder.addClassName("entries-load-older");
        Div more = new Div(loadOlder);
        more.addClassName("entries-more");
        add(eyebrow, new H1(getTranslation("entries.journal.title")), days, message, progress, more);
        loadOlder.setVisible(false);
        loadNext();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        // Day names depend on the browser time zone, which is only known after a round trip: redraw once it is.
        BrowserZone.init(attachEvent.getUI(), () -> {
            if (!loaded.isEmpty()) {
                render(-1);
            }
        });
    }

    @Override
    public String getPageTitle() {
        return getTranslation("entries.journal.title");
    }

    private void loadNext() {
        boolean append = !loaded.isEmpty();
        PageDto<EntryDto> result;
        try {
            result = entries.list(nextPage, PAGE_SIZE);
        } catch (RuntimeException e) {
            message.setText(getTranslation(append ? "entries.journal.loadMoreFailed" : "entries.journal.loadFailed"));
            message.setVisible(true);
            loadOlder.setVisible(append);
            return;
        }
        message.setVisible(false);
        List<EntryDto> before = loaded;
        loaded = EntryListPresenter.merge(before, result.items());
        nextPage = result.page() + 1;
        total = result.total();
        render(append ? EntryListPresenter.firstNewId(before, loaded) : -1);
        loadOlder.setVisible(EntryListPresenter.Paging.of(result).hasNext() && !result.items().isEmpty());
        if (append && focusTarget != null) {
            // The button the user pressed may be gone (last page); keyboard focus goes to the first new entry.
            focusTarget.getElement().executeJs("this.focus()");
        }
    }

    private void render(long focusId) {
        days.removeAll();
        focusTarget = null;
        if (total == 0 && loaded.isEmpty()) {
            eyebrow.setVisible(false);
            message.setText(getTranslation("entries.journal.empty"));
            message.setVisible(true);
            progress.setText("");
            return;
        }
        eyebrow.setText(getTranslation(total == 1 ? "entries.journal.count.one" : "entries.journal.count", total));
        eyebrow.setVisible(true);
        Locale locale = UI.getCurrent().getLocale();
        LocalDate today = BrowserZone.current().today();
        List<Row> rows = loaded.stream().map(e -> EntryListPresenter.row(e, locale)).toList();
        for (DayGroup group : EntryListPresenter.groupByDay(rows)) {
            days.add(daySection(group, today, locale, focusId));
        }
        progress.setText(getTranslation("entries.journal.loaded", loaded.size(), total));
    }

    private Section daySection(DayGroup group, LocalDate today, Locale locale, long focusId) {
        Section section = new Section();
        section.addClassName("entries-day");
        H2 heading = new H2();
        heading.addClassName("entries-day__heading");
        Span label;
        if (group.day() == null) {
            label = new Span(getTranslation("entries.untitled"));
            heading.add(label);
        } else {
            DayHeader header = EntryListPresenter.dayHeader(group.day(), today, locale,
                    getTranslation("entries.journal.today"), getTranslation("entries.journal.yesterday"));
            label = new Span(header.label());
            Span detail = new Span(header.detail());
            detail.addClassNames("entries-day__detail", "diary-muted");
            heading.add(label, detail);
        }
        UnorderedList list = new UnorderedList();
        list.addClassName("entries-list");
        for (Row row : group.rows()) {
            list.add(card(row, row.id() == focusId));
        }
        section.add(heading, list);
        return section;
    }

    private ListItem card(Row row, boolean focus) {
        ListItem item = new ListItem();
        item.addClassNames("diary-card", "entries-card");

        RouterLink link = new RouterLink();
        link.setRoute(EntryDetailView.class, new RouteParameters("id", String.valueOf(row.id())));
        link.addClassName("entries-card__link");
        if (focus) {
            focusTarget = link;
        }

        Div meta = new Div();
        meta.addClassName("entries-meta");
        Span time = new Span(row.time());
        time.addClassNames("entries-time", "diary-muted");
        meta.add(time);
        if (row.voice()) {
            meta.add(chip(getTranslation("entries.badge.voice"), "mic", false));
        }
        if (row.hasCoachConversation()) {
            meta.add(chip(getTranslation("entries.badge.coach"), "chat", true));
        }
        if (!row.finished()) {
            Span status = chip(getTranslation(row.statusKey()), null, false);
            status.addClassName("entries-chip-status");
            meta.add(status);
        }
        link.add(meta);
        if (!row.preview().isBlank()) {
            Paragraph excerpt = new Paragraph(row.preview());
            excerpt.addClassName("entries-excerpt");
            link.add(excerpt);
        }
        item.add(link);

        if (row.hasLegacyRemark()) {
            Details remark = new Details(getTranslation("entries.legacyRemark"), new Paragraph(row.legacyRemark()));
            remark.addClassName("entries-remark");
            remark.setOpened(false);
            item.add(remark);
        }
        return item;
    }

    /** Colour is never the only signal: every chip carries a text label (and a shape icon where it has one). */
    static Span chip(String text, String icon, boolean accent) {
        Span chip = new Span();
        chip.addClassName("diary-chip");
        if (accent) {
            chip.addClassName("diary-chip-accent");
        }
        if (icon != null) {
            chip.add(DiaryIcons.create(icon));
        }
        chip.add(new Span(text));
        return chip;
    }
}
