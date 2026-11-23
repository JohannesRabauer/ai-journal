package dev.rabauer.ai.diary.views.entries;

import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.value.ValueChangeMode;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.entry.CreateEntryRequest;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.UpdateEntryRequest;
import dev.rabauer.ai.diary.client.EntriesClient;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Text area with save-first autosave: debounced while typing (lazy value change), immediately on blur and when the
 * view goes away. Shows a quiet status line, never AI output. A failed save keeps the text and retries on the next
 * trigger.
 */
public class AutosaveEditor extends Composite<VerticalLayout> {

    private final EntriesClient entries;
    private final Texts texts;
    private final Supplier<Language> language;
    private final Consumer<EntryDto> onEntrySaved;
    private final TextArea area = new TextArea();
    private final Span status = new Span();
    private AutosavePresenter presenter;

    /**
     * @param presenter    fresh for a new entry, or {@link AutosavePresenter#forExisting} for an existing one
     * @param onEntrySaved called after every successful save with the stored entry
     */
    public AutosaveEditor(EntriesClient entries, Texts texts, Supplier<Language> language, AutosavePresenter presenter,
                          String initialText, Consumer<EntryDto> onEntrySaved) {
        this.entries = entries;
        this.texts = texts;
        this.language = language;
        this.presenter = presenter;
        this.onEntrySaved = onEntrySaved;

        area.setWidthFull();
        area.setMinHeight("12em");
        area.setMaxHeight("60vh");
        area.setValue(initialText == null ? "" : initialText);
        area.setValueChangeMode(ValueChangeMode.LAZY);
        area.setValueChangeTimeout(AutosavePresenter.DEBOUNCE_MS);
        area.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                save();
            }
        });
        // Vaadin flushes a lazy field on its change event (which precedes blur), so blur needs no extra listener.

        status.getElement().setAttribute("role", "status");
        status.getElement().setAttribute("aria-live", "polite");
        status.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");

        VerticalLayout root = getContent();
        root.setPadding(false);
        root.setSpacing(false);
        root.add(area, status);
    }

    public TextArea textArea() {
        return area;
    }

    public String value() {
        return area.getValue();
    }

    public Long entryId() {
        return presenter.entryId();
    }

    /** Clears the editor and starts over with a new (not yet created) entry. */
    public void startNewEntry() {
        presenter = AutosavePresenter.forNewEntry();
        area.clear();
        status.setText("");
    }

    /** Saves if there is anything unsaved. Safe to call at any time; failures are shown, not thrown. */
    public boolean save() {
        String text = area.getValue();
        try {
            switch (presenter.decide(text)) {
                case NONE -> {
                    return true;
                }
                case CREATE -> {
                    Language lang = language.get();
                    EntryDto created = entries.create(new CreateEntryRequest(text, null,
                            lang == Language.AUTO ? null : lang, EntrySource.TEXT));
                    presenter.saved(created.id(), text);
                    onSaved(created);
                }
                case UPDATE -> {
                    EntryDto updated = entries.update(presenter.entryId(), new UpdateEntryRequest(text, null));
                    presenter.saved(updated.id(), text);
                    onSaved(updated);
                }
            }
            status.setText(texts.get("entries.autosave.saved"));
            return true;
        } catch (RuntimeException e) {
            // BackendException or an unreachable backend: keep the text, say so calmly, retry on the next trigger.
            status.setText(texts.get("entries.autosave.failed"));
            return false;
        }
    }

    private void onSaved(EntryDto entry) {
        onEntrySaved.accept(entry);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        save();
        super.onDetach(detachEvent);
    }
}
