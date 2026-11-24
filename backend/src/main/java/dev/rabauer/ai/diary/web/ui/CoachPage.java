package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.coach.AnswerRequest;
import dev.rabauer.ai.diary.api.coach.CoachRole;
import dev.rabauer.ai.diary.api.coach.ConversationDto;
import dev.rabauer.ai.diary.api.coach.StartConversationRequest;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.api.settings.SettingsDto;
import dev.rabauer.ai.diary.coach.CoachService;
import dev.rabauer.ai.diary.entry.EntryService;
import dev.rabauer.ai.diary.security.Csrf;
import dev.rabauer.ai.diary.settings.SettingsService;
import dev.rabauer.ai.diary.web.ApiException;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The coach conversation of an entry (SPEC sections 5, 9, 12): the panel shown beside the entry on
 * {@code /journal/{id}} and the full page {@code /journal/{id}/reflect}. The routes are declared in
 * {@link JournalResource} (one URL prefix belongs to one resource class). The conversation is rendered by the server;
 * the script ({@code js/coach.js}) only calls the JSON API and swaps the panel body with the server's fragment. Without
 * JavaScript the forms post here (CSRF token first) and answer 303 to the full page.
 *
 * <p>The coach is user-initiated: nothing here starts a conversation on its own, and journal text is never logged.
 */
@ApplicationScoped
public class CoachPage {

    @CheckedTemplate(basePath = "JournalResource")
    static class Templates {
        static native TemplateInstance reflect(Shell shell, Messages t, CoachPresenter.View c, long entryId);
    }

    @Inject
    @Location("JournalResource/coachBody")
    Template body;
    @Inject
    CoachService coach;
    @Inject
    EntryService entries;
    @Inject
    SettingsService settings;
    @Inject
    ShellFactory shells;
    @Inject
    Clock clock;
    @Inject
    Csrf csrf;

    /** Everything the panel needs for one entry; {@code fresh} shows the start state even when conversations exist. */
    public CoachPresenter.View view(EntryDto entry, Messages t, ZoneId zone, boolean fullPage, boolean fresh,
                                    CoachPresenter.ErrorInfo error, String draft) {
        long id = entry.id();
        ConversationDto conversation = null;
        CoachPresenter.State state = CoachPresenter.State.NOT_FINISHED;
        if (entry.status() == EntryStatus.FINISHED) {
            state = CoachPresenter.State.NOT_STARTED;
            if (!fresh) {
                conversation = CoachPresenter.latest(coach.forEntry(id)).orElse(null);
                if (conversation != null) {
                    state = CoachPresenter.State.valueOf(conversation.status().name());
                }
            }
        }
        boolean autoPlay = false;
        String voiceDe = null;
        String voiceEn = null;
        try {
            SettingsDto s = settings.get();
            autoPlay = s.ttsAutoPlay() == null || s.ttsAutoPlay();
            voiceDe = s.ttsVoiceDe();
            voiceEn = s.ttsVoiceEn();
        } catch (RuntimeException e) {
            // unknown setting: stay silent
        }
        Language convLanguage = conversation == null ? null : conversation.language();
        CoachPresenter.Actions actions = conversation == null ? new CoachPresenter.Actions(false, false, false, false, false)
                : CoachPresenter.actions(conversation.status());
        boolean extract = conversation != null && conversation.messages().stream().anyMatch(m -> m.role() == CoachRole.USER);
        String reflect = "/journal/" + id + "/reflect";
        return new CoachPresenter.View(id, state, t.get(fullPage ? "coach.transcript.label" : "coach.panel.title"), fullPage,
                conversation == null ? 0 : conversation.id(), convLanguage == null ? "auto" : convLanguage.code(),
                CoachPresenter.speechLanguage(convLanguage, t.locale()).name(), autoPlay, voiceDe, voiceEn,
                conversation == null ? "" : t.get("coach.status." + conversation.status().name()),
                conversation == null ? List.of() : CoachPresenter.messages(conversation, t, zone),
                actions.answer(), actions.pause(), actions.resume(), actions.end(), actions.startNew(), extract,
                conversation == null ? null : "/memory?source=conversation&id=" + conversation.id(),
                reflect, reflect + "?new=1#coach", reflect + "?fragment=1",
                error == null ? null : t.get(error.messageKey()), draft == null ? "" : draft,
                PageTexts.json(t, PageTexts.COACH_KEYS), PageTexts.coachVoiceLabels(t), PageTexts.coachSpeechLabels(t));
    }

    private Response notFound(Messages t) {
        return Response.status(Response.Status.NOT_FOUND).type(MediaType.TEXT_HTML_TYPE)
                .entity(EntryPage.Templates.entryNotFound(shells.create(t, "/journal"), t)).build();
    }

    /** The full page. */
    public Response renderPage(long id, boolean fresh, CoachPresenter.ErrorInfo error, String draft,
                               List<Locale> acceptable, String zoneCookie) {
        Messages t = Messages.forAcceptable(acceptable);
        EntryDto entry;
        try {
            entry = entries.get(id);
        } catch (ApiException e) {
            if (e.status() == Response.Status.NOT_FOUND) {
                return notFound(t);
            }
            throw e;
        }
        CoachPresenter.View view = view(entry, t, ClientZone.resolve(zoneCookie, clock.getZone()), true, fresh, error, draft);
        return Response.ok(Templates.reflect(shells.create(t, "/journal"), t, view, id), MediaType.TEXT_HTML_TYPE).build();
    }

    /** Only the panel body, for the script's in-place update. */
    public Response renderFragment(long id, boolean fresh, List<Locale> acceptable, String zoneCookie) {
        Messages t = Messages.forAcceptable(acceptable);
        EntryDto entry;
        try {
            entry = entries.get(id);
        } catch (ApiException e) {
            if (e.status() == Response.Status.NOT_FOUND) {
                return Response.status(Response.Status.NOT_FOUND).type(MediaType.TEXT_HTML_TYPE).entity("").build();
            }
            throw e;
        }
        CoachPresenter.View view = view(entry, t, ClientZone.resolve(zoneCookie, clock.getZone()), true, fresh, null, null);
        return Response.ok(body.data("t", t).data("c", view).render(), MediaType.TEXT_HTML_TYPE).build();
    }

    // ---- no-JavaScript actions: token first, then the same service calls the JSON API makes, then 303 ----

    public Response start(long id, String token, List<Locale> acceptable, String zoneCookie) {
        csrf.require(token);
        return act(id, "", acceptable, zoneCookie, () -> coach.start(new StartConversationRequest(id)));
    }

    /** A blank answer is ignored; a failed send shows the page again with the text still in the field. */
    public Response answer(long id, String text, String token, List<Locale> acceptable, String zoneCookie) {
        csrf.require(token);
        if (text == null || text.isBlank()) {
            return back(id);
        }
        return act(id, text, acceptable, zoneCookie, () -> {
            ConversationDto c = latestOf(id);
            return c == null ? null : coach.answer(c.id(), new AnswerRequest(text.strip(), false));
        });
    }

    public Response pause(long id, String token, List<Locale> acceptable, String zoneCookie) {
        csrf.require(token);
        return act(id, "", acceptable, zoneCookie, () -> {
            ConversationDto c = latestOf(id);
            return c == null ? null : coach.pause(c.id());
        });
    }

    public Response resume(long id, String token, List<Locale> acceptable, String zoneCookie) {
        csrf.require(token);
        return act(id, "", acceptable, zoneCookie, () -> {
            ConversationDto c = latestOf(id);
            return c == null ? null : coach.resume(c.id());
        });
    }

    public Response end(long id, String token, List<Locale> acceptable, String zoneCookie) {
        csrf.require(token);
        return act(id, "", acceptable, zoneCookie, () -> {
            ConversationDto c = latestOf(id);
            return c == null ? null : coach.end(c.id());
        });
    }

    private ConversationDto latestOf(long entryId) {
        return CoachPresenter.latest(coach.forEntry(entryId)).orElse(null);
    }

    private Response act(long id, String draft, List<Locale> acceptable, String zoneCookie,
                         Supplier<ConversationDto> call) {
        try {
            call.get();
        } catch (RuntimeException e) {
            CoachPresenter.ErrorInfo info = CoachPresenter.errorFor(e);
            return renderPage(id, false, info, draft, acceptable, zoneCookie);
        }
        return back(id);
    }

    private static Response back(long id) {
        return Response.seeOther(URI.create("/journal/" + id + "/reflect#coach")).build();
    }
}
