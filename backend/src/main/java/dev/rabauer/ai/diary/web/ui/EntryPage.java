package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.audio.RecordingDto;
import dev.rabauer.ai.diary.api.audio.RecordingStatus;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.UpdateEntryRequest;
import dev.rabauer.ai.diary.audio.RecordingService;
import dev.rabauer.ai.diary.entry.EntryService;
import dev.rabauer.ai.diary.security.Csrf;
import dev.rabauer.ai.diary.web.ApiException;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * The entry detail page {@code /journal/{id}} and its no-JavaScript form actions. The routes are declared in
 * {@link JournalResource} (one URL prefix belongs to one resource class); this bean holds everything else so the two
 * phases that edit that class do not collide.
 */
@ApplicationScoped
public class EntryPage {

    @CheckedTemplate(basePath = "JournalResource")
    static class Templates {
        static native TemplateInstance entry(Shell shell, Messages t, EntryPresenter.View v, String texts,
                                             String audioLabels);

        static native TemplateInstance entryNotFound(Shell shell, Messages t);
    }

    @Inject
    EntryService entries;
    @Inject
    RecordingService recordings;
    @Inject
    ShellFactory shells;
    @Inject
    Csrf csrf;

    public Response render(long id, String notice, boolean manual, List<Locale> acceptable) {
        Messages t = Messages.forAcceptable(acceptable);
        Shell shell = shells.create(t, "/journal");
        EntryDto entry;
        try {
            entry = entries.get(id);
        } catch (ApiException e) {
            if (e.status() == Response.Status.NOT_FOUND) {
                return Response.status(Response.Status.NOT_FOUND).type(MediaType.TEXT_HTML_TYPE)
                        .entity(Templates.entryNotFound(shell, t)).build();
            }
            throw e;
        }
        String recordingId = null;
        boolean audioDeleted = false;
        if (entry.recordingId() != null) {
            try {
                RecordingDto recording = recordings.get(entry.recordingId());
                if (recording.status() == RecordingStatus.COMPLETE) {
                    recordingId = entry.recordingId().toString();
                } else if (recording.status() == RecordingStatus.AUDIO_DELETED) {
                    audioDeleted = true;
                }
            } catch (RuntimeException e) {
                // audio status unknown: show the entry without a player
            }
        }
        EntryPresenter.View view = EntryPresenter.view(entry, recordingId, audioDeleted, manual,
                EntryPresenter.Notice.of(notice), t);
        String texts = PageTexts.json(t, PageTexts.PANEL_KEYS);
        return Response.ok(Templates.entry(shell, t, view, texts, PageTexts.audioLabels(t)), MediaType.TEXT_HTML_TYPE).build();
    }

    // ---- no-JavaScript actions: token first, then the same service calls the JSON API makes, then 303 ----

    /** Saves the text (and finishes the entry when {@code finish} is set). */
    public Response saveText(long id, String text, boolean finish, String token) {
        csrf.require(token);
        try {
            entries.update(id, new UpdateEntryRequest(text == null ? "" : text, null));
            if (finish) {
                entries.finish(id);
            }
        } catch (ApiException e) {
            return back(id, notFoundOr(e, EntryPresenter.Notice.FAILED));
        }
        return back(id, finish ? EntryPresenter.Notice.FINISHED : EntryPresenter.Notice.SAVED);
    }

    public Response deleteAudio(long id, String token) {
        csrf.require(token);
        try {
            EntryDto entry = entries.get(id);
            if (entry.recordingId() != null) {
                recordings.deleteAudio(entry.recordingId());
            }
        } catch (ApiException e) {
            return back(id, notFoundOr(e, EntryPresenter.Notice.FAILED));
        }
        return back(id, EntryPresenter.Notice.AUDIO_DELETED);
    }

    public Response deleteEntry(long id, String token) {
        csrf.require(token);
        try {
            entries.delete(id);
        } catch (ApiException e) {
            if (e.status() != Response.Status.NOT_FOUND) {
                return back(id, EntryPresenter.Notice.FAILED);
            }
        }
        return Response.seeOther(URI.create("/journal")).build();
    }

    private static EntryPresenter.Notice notFoundOr(ApiException e, EntryPresenter.Notice other) {
        return e.status() == Response.Status.NOT_FOUND ? null : other;
    }

    private static Response back(long id, EntryPresenter.Notice notice) {
        String target = "/journal/" + id + (notice == null ? "" : "?notice=" + notice.code()) + "#entry-title";
        return Response.seeOther(URI.create(target)).build();
    }
}
