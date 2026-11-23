package dev.rabauer.ai.diary.views.entries;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.component.UI;
import dev.rabauer.ai.diary.api.common.PageDto;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.client.EntriesClient;
import dev.rabauer.ai.diary.shell.MainLayout;
import dev.rabauer.ai.diary.views.entries.EntryListPresenter.Paging;
import dev.rabauer.ai.diary.views.entries.EntryListPresenter.Row;

/** Journal: newest-first, paged list of all entries. No AI output; legacy AI remarks stay collapsed. */
@Route(value = "entries", layout = MainLayout.class)
public class EntriesView extends VerticalLayout implements HasDynamicTitle {

    static final int PAGE_SIZE = 20;

    private final EntriesClient entries;
    private final VerticalLayout list = new VerticalLayout();
    private final HorizontalLayout pager = new HorizontalLayout();
    private int page;

    public EntriesView(EntriesClient entries) {
        this.entries = entries;
        setMaxWidth("48rem");
        add(new H2(getTranslation("entries.journal.title")), list, pager);
        list.setPadding(false);
        pager.setAlignItems(FlexComponent.Alignment.CENTER);
        load(0);
    }

    @Override
    public String getPageTitle() {
        return getTranslation("entries.journal.title");
    }

    private void load(int requestedPage) {
        list.removeAll();
        pager.removeAll();
        PageDto<EntryDto> result;
        try {
            result = entries.list(requestedPage, PAGE_SIZE);
        } catch (RuntimeException e) {
            list.add(new Paragraph(getTranslation("entries.journal.loadFailed")));
            return;
        }
        page = result.page();
        if (result.items().isEmpty()) {
            list.add(new Paragraph(getTranslation("entries.journal.empty")));
        }
        for (EntryDto e : result.items()) {
            list.add(card(EntryListPresenter.row(e, UI.getCurrent().getLocale())));
        }
        Paging paging = Paging.of(result);
        if (paging.pageCount() > 1) {
            Button previous = new Button(getTranslation("entries.journal.previous"), e -> load(page - 1));
            previous.setEnabled(paging.hasPrevious());
            Button next = new Button(getTranslation("entries.journal.next"), e -> load(page + 1));
            next.setEnabled(paging.hasNext());
            Span label = new Span(getTranslation("entries.journal.page", paging.displayPage(), paging.displayPageCount()));
            pager.add(previous, label, next);
        }
    }

    private Div card(Row row) {
        Div card = new Div();
        card.getStyle().set("border", "1px solid var(--lumo-contrast-20pct)")
                .set("border-radius", "var(--lumo-border-radius-l)").set("padding", "var(--lumo-space-m)")
                .set("width", "100%").set("box-sizing", "border-box");

        HorizontalLayout head = new HorizontalLayout();
        head.setAlignItems(FlexComponent.Alignment.CENTER);
        head.add(EntryDetailView.link(row.date().isBlank() ? getTranslation("entries.untitled") : row.date(), row.id()));
        if (row.voice()) {
            head.add(badge(getTranslation("entries.badge.voice")));
        }
        if (!row.finished()) {
            head.add(badge(getTranslation(row.statusKey())));
        }
        if (row.hasCoachConversation()) {
            head.add(badge(getTranslation("entries.badge.coach")));
        }
        card.add(head);
        if (!row.preview().isBlank()) {
            card.add(new Paragraph(row.preview()));
        }
        if (row.hasLegacyRemark()) {
            Details remark = new Details(getTranslation("entries.legacyRemark"), new Paragraph(row.legacyRemark()));
            remark.setOpened(false);
            card.add(remark);
        }
        return card;
    }

    private static Span badge(String text) {
        Span span = new Span(text);
        span.getElement().getThemeList().add("badge contrast");
        return span;
    }
}
