package dev.rabauer.ai.diary.views.today;

import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.api.transcription.TranscriptionJobDto;
import dev.rabauer.ai.diary.api.transcription.TranscriptionStatus;

import java.util.Optional;

/** Stop conditions for polling the transcription of an entry (pure, no Vaadin types). */
public final class TranscriptionPolling {

    public static final int INTERVAL_MS = 2000;
    /** About 30 minutes; long recordings can take a while. After that we stop quietly, the entry is not affected. */
    public static final int MAX_POLLS = 900;

    public enum Decision { CONTINUE, SUCCEEDED, FAILED, GIVE_UP }

    private TranscriptionPolling() {
    }

    /**
     * @param entryStatus current entry status (source of truth once the backend moved the entry on)
     * @param job         latest job; empty while the job has not been created yet
     * @param polls       number of polls done so far, including this one
     */
    public static Decision decide(EntryStatus entryStatus, Optional<TranscriptionJobDto> job, int polls) {
        if (entryStatus == EntryStatus.TRANSCRIBED || entryStatus == EntryStatus.FINISHED) {
            return Decision.SUCCEEDED;
        }
        if (entryStatus == EntryStatus.TRANSCRIPTION_FAILED) {
            return Decision.FAILED;
        }
        if (job.isPresent() && job.get().status() == TranscriptionStatus.FAILED) {
            return Decision.FAILED;
        }
        // QUEUED, RUNNING, no job yet, or SUCCEEDED while the entry has not switched yet: poll again (bounded).
        return polls >= MAX_POLLS ? Decision.GIVE_UP : Decision.CONTINUE;
    }

    /** True while the entry should show transcription progress. */
    public static boolean isInProgress(EntryStatus s) {
        return s == EntryStatus.RECORDED || s == EntryStatus.TRANSCRIBING;
    }
}
