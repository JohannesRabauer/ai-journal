package dev.rabauer.ai.diary.views.entries;

/**
 * Save-first autosave decisions (SPEC 4.2): the entry is created on the first non-blank text, then updated whenever
 * the text differs from what was last saved. The debounce itself is done by the text field; this class only decides
 * what a save has to do. A failed save leaves the state untouched so the next trigger retries. No Vaadin types.
 */
public final class AutosavePresenter {

    /** Idle time after the last keystroke before the field fires a save. */
    public static final int DEBOUNCE_MS = 2000;

    public enum Action { NONE, CREATE, UPDATE }

    private Long entryId;
    private String lastSaved = "";

    public static AutosavePresenter forNewEntry() {
        return new AutosavePresenter();
    }

    public static AutosavePresenter forExisting(long entryId, String text) {
        AutosavePresenter p = new AutosavePresenter();
        p.entryId = entryId;
        p.lastSaved = text == null ? "" : text;
        return p;
    }

    public Action decide(String current) {
        String text = current == null ? "" : current;
        if (entryId == null) {
            return text.isBlank() ? Action.NONE : Action.CREATE;
        }
        return text.equals(lastSaved) ? Action.NONE : Action.UPDATE;
    }

    /** Call after the backend accepted the text. */
    public void saved(long id, String text) {
        this.entryId = id;
        this.lastSaved = text == null ? "" : text;
    }

    public Long entryId() {
        return entryId;
    }

    public boolean isDirty(String current) {
        return decide(current) != Action.NONE;
    }

    public void reset() {
        entryId = null;
        lastSaved = "";
    }
}
