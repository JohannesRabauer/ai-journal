package dev.rabauer.ai.diary;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.Route;
import dev.rabauer.ai.diary.api.entry.CreateEntryRequest;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.client.BackendException;
import dev.rabauer.ai.diary.client.EntriesClient;
import dev.rabauer.ai.diary.views.EntryFormat;

/**
 * Wave 0 interim view: save-first text entries and the newest-first list. No AI output anywhere.
 * Replaced by the design shell (W1c) and recorder/entries views (W1b).
 */
@Route("")
public class MainView extends VerticalLayout {

    private final EntriesClient entries;
    private final VerticalLayout list = new VerticalLayout();

    public MainView(EntriesClient entries) {
        this.entries = entries;
        setSizeFull();

        TextArea input = new TextArea("New entry");
        input.setWidthFull();
        input.setMinHeight("8em");
        Button save = new Button("Save entry", e -> save(input));
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        list.setPadding(false);
        add(input, save, list);
        reload();
    }

    private void save(TextArea input) {
        String text = input.getValue();
        if (text.isBlank()) {
            return;
        }
        try {
            EntryDto created = entries.create(new CreateEntryRequest(text, null, null, EntrySource.TEXT));
            entries.finish(created.id());
            input.clear();
            reload();
        } catch (BackendException e) {
            Notification.show(e.error().message());
        } catch (RuntimeException e) {
            Notification.show("The diary service is not reachable. Your text is still in the editor.");
        }
    }

    private void reload() {
        list.removeAll();
        try {
            entries.list(0, 20).items().forEach(e -> list.add(card(e)));
        } catch (RuntimeException e) {
            list.add(new Paragraph("Entries could not be loaded."));
        }
    }

    private Div card(EntryDto e) {
        Div card = new Div(new H3(EntryFormat.title(e)), new Paragraph(e.text()));
        card.setWidthFull();
        if (e.legacyAiRemark() != null && !e.legacyAiRemark().isBlank()) {
            Details remark = new Details("Earlier AI remark", new Paragraph(e.legacyAiRemark()));
            remark.setOpened(false);
            card.add(remark);
        }
        if (e.status() != EntryStatus.FINISHED) {
            card.add(new Button("Finish entry", ev -> {
                entries.finish(e.id());
                reload();
            }));
        }
        return card;
    }
}
