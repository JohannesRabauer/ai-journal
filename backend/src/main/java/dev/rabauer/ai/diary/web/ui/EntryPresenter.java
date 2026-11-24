package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;

/**
 * Pure view logic of the entry detail page (port of the Vaadin EntryDetailView/EntryEditorPanel decisions): which
 * panel, chips and actions an entry shows in its state. No framework types; the resource only gathers data.
 */
public final class EntryPresenter {

    /** What the text panel shows: transcription progress, the failed-transcription notice, the editor, or nothing. */
    public enum Panel { TRANSCRIBING, FAILED, EDIT, NONE }

    /** Flash notices, passed as a short code in the query (never journal text). */
    public enum Notice {
        SAVED("entries.autosave.saved"), FINISHED("entries.finished"), AUDIO_DELETED("entries.audio.deleted"),
        FAILED("entries.autosave.failed");

        final String key;

        Notice(String key) {
            this.key = key;
        }

        public static Notice of(String code) {
            if (code == null) {
                return null;
            }
            return switch (code) {
                case "saved" -> SAVED;
                case "finished" -> FINISHED;
                case "audioDeleted" -> AUDIO_DELETED;
                case "failed" -> FAILED;
                default -> null;
            };
        }

        public String code() {
            return switch (this) {
                case SAVED -> "saved";
                case FINISHED -> "finished";
                case AUDIO_DELETED -> "audioDeleted";
                case FAILED -> "failed";
            };
        }
    }

    private EntryPresenter() {
    }

    /**
     * @param statusLabel     null for finished entries (it would be noise)
     * @param panelLabel      label of the text area; {@code panelHelper} is only set while reviewing a transcript
     * @param recordingId     id of the complete recording, null without playable audio
     * @param notice          calm one-line result of a no-JavaScript action, or null
     * @param typeManuallyHref link of the failed-transcription notice for people without JavaScript
     */
    public record View(long id, String eyebrow, String heading, boolean voice, boolean coach, String statusLabel,
                       String status, boolean finished, String recordingId, boolean audioDeleted, String text,
                       Panel panel, String panelLabel, String panelHelper, boolean showFinish, boolean finishDisabled,
                       String typeManuallyHref, String legacyRemark, boolean reflectEnabled,
                       String reflectHref, String reflectLabel, String reflectText, boolean showEdit,
                       boolean showExtract, String extractHref, boolean showDeleteAudio, boolean showDeleteEntry,
                       String notice) {
        public boolean hasAudio() {
            return recordingId != null;
        }

        public boolean panelTranscribing() {
            return panel == Panel.TRANSCRIBING;
        }

        public boolean panelFailed() {
            return panel == Panel.FAILED;
        }

        public boolean panelEdit() {
            return panel == Panel.EDIT;
        }
    }

    /**
     * @param hasAudio     a complete recording exists (the player is shown)
     * @param audioDeleted the recording's audio was deleted (a quiet note replaces the player)
     * @param manualMode   {@code ?manual=1}: the user chose to write the entry after a failed transcription
     */
    public static View view(EntryDto e, String recordingId, boolean audioDeleted, boolean manualMode, Notice notice,
                            Messages t) {
        boolean hasAudio = recordingId != null;
        boolean hasText = e.text() != null && !e.text().isBlank();
        EntryActions actions = EntryActions.forEntry(e.status(), hasAudio, hasText, manualMode);
        boolean finished = e.status() == EntryStatus.FINISHED;
        boolean reviewing = e.status() == EntryStatus.TRANSCRIBED;

        Panel panel = actions.transcribing() ? Panel.TRANSCRIBING
                : actions.editText() ? Panel.EDIT
                : actions.retryTranscription() || actions.typeManually() ? Panel.FAILED : Panel.NONE;
        String heading = EntryFormat.heading(e.text());
        String remark = e.legacyAiRemark() == null || e.legacyAiRemark().isBlank() ? null : e.legacyAiRemark();

        return new View(e.id(), EntryFormat.detailEyebrow(e, t.locale()), heading.isBlank() ? t.get("entries.untitled") : heading,
                e.source() == EntrySource.VOICE, e.hasCoachConversation(),
                finished ? null : t.get("entries.status." + e.status().name()), e.status().name(), finished,
                recordingId, audioDeleted, e.text() == null ? "" : e.text(), panel,
                t.get(reviewing ? "entries.review.label" : "entries.text.label"),
                reviewing ? t.get("entries.review.helper") : null,
                panel == Panel.EDIT && EntryActions.forEntry(e.status(), hasAudio, true, manualMode).finish(), !hasText,
                actions.typeManually() ? "/journal/" + e.id() + "?manual=1#entry-text" : null,
                remark, finished, "/journal/" + e.id() + "/reflect",
                t.get(e.hasCoachConversation() ? "coach.entry.reflect.open" : "entries.reflect"),
                t.get(finished ? "entries.reflect.text" : "coach.notFinished"),
                actions.editText(), finished && hasText, "/memory?source=entry&id=" + e.id(),
                hasAudio && actions.deleteAudio(), actions.deleteEntry(),
                notice == null ? null : t.get(notice.key));
    }
}
