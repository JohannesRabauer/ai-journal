package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.privacy.DeleteAllRequest;
import dev.rabauer.ai.diary.api.settings.SettingsDto;
import dev.rabauer.ai.diary.api.system.SystemInfoDto;
import dev.rabauer.ai.diary.privacy.PrivacyService;
import dev.rabauer.ai.diary.security.Csrf;
import dev.rabauer.ai.diary.settings.SettingsService;
import dev.rabauer.ai.diary.system.SystemInfoService;
import dev.rabauer.ai.diary.web.ApiException;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Settings page. Reading is plain HTML; the form works without JavaScript ({@code POST /settings} with the form
 * token, then a redirect with a flash code) and {@code js/settings.js} saves in place through {@code PUT /api/settings}.
 * Export is a plain GET link/form to {@code /api/data/export}; delete-everything keeps its typed phrase.
 */
@Path("/settings")
@Produces(MediaType.TEXT_HTML)
public class SettingsPage {

    private static final Logger LOG = Logger.getLogger(SettingsPage.class);

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance page(Shell shell, Messages t, View v);
    }

    public record Option(String value, String label, boolean selected) {
    }

    public record View(List<Option> uiLanguages, List<Option> entryLanguages, boolean keepAudio, boolean ttsAutoPlay,
                       String intervals, String intervalsError, Flash flash, boolean profileAvailable,
                       String profileLine, String cloudLine, List<String> providers, String phrase,
                       String rangeError, String countError) {
    }

    @Inject
    SettingsService settings;
    @Inject
    SystemInfoService system;
    @Inject
    PrivacyService privacy;
    @Inject
    ShellFactory shells;
    @Inject
    Csrf csrf;

    @GET
    public TemplateInstance page(@QueryParam("msg") String msg, @QueryParam("error") String error,
                                 @Context HttpHeaders headers) {
        Messages t = Messages.forAcceptable(headers.getAcceptableLanguages());
        SettingsDto s;
        Flash flash = flashFor(t, msg);
        String intervalsError = null;
        try {
            s = settings.get();
        } catch (RuntimeException e) {
            s = SettingsService.DEFAULTS;
            flash = new Flash(t.get("settings.error.load"), true);
        }
        if (error != null && java.util.Set.of("intervals-empty", "intervals-format", "intervals-range", "intervals-order", "intervals-count").contains(error)) {
            intervalsError = intervalsMessage(t, "settings.intervals.error." + error.substring("intervals-".length()));
        }
        return Templates.page(shells.create(t, "/settings"), t, view(t, s, flash, intervalsError));
    }

    /** No-JavaScript save: the whole form is posted, only what differs from the stored settings is written. */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response save(@FormParam("uiLanguage") String uiLanguage, @FormParam("entryLanguage") String entryLanguage,
                         @FormParam("keepAudio") String keepAudio, @FormParam("ttsAutoPlay") String ttsAutoPlay,
                         @FormParam("intervals") String intervals, @FormParam("_csrf") String token,
                         @Context HttpHeaders headers) {
        csrf.require(token);
        IntervalsParser.Result parsed = IntervalsParser.parse(intervals);
        if (!parsed.valid()) {
            return redirect("/settings?error=intervals-" + parsed.errorKey().substring("settings.intervals.error.".length()));
        }
        Language ui = language(uiLanguage);
        Language entry = language(entryLanguage);
        if (ui == null || entry == null) {
            return redirect("/settings?msg=invalid");
        }
        try {
            SettingsDto current = settings.get();
            SettingsDto edited = new SettingsDto(ui, entry, keepAudio != null, ttsAutoPlay != null, null, null, parsed.values());
            SettingsDto partial = SettingsDiff.partial(current, edited);
            if (SettingsDiff.isEmpty(partial)) {
                return redirect("/settings?msg=nothing");
            }
            settings.update(partial);
            return redirect("/settings?msg=saved");
        } catch (ApiException e) {
            return redirect("/settings?msg=invalid");
        } catch (RuntimeException e) {
            return redirect("/settings?msg=unreachable");
        }
    }

    /** No-JavaScript delete-everything: the typed phrase must match exactly, as in the JSON API. */
    @POST
    @Path("delete-all")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response deleteAll(@FormParam("confirmation") String confirmation, @FormParam("_csrf") String token) {
        csrf.require(token);
        if (!DeleteConfirmation.isConfirmed(confirmation)) {
            return redirect("/settings?msg=confirmation");
        }
        try {
            privacy.deleteAll();
            return redirect("/settings?msg=deleted");
        } catch (RuntimeException e) {
            LOG.warnf("Delete-all from the settings page failed kind=%s", e.getClass().getSimpleName());
            return redirect("/settings?msg=deleteFailed");
        }
    }

    // ------------------------------------------------------------------ view model

    View view(Messages t, SettingsDto s, Flash flash, String intervalsError) {
        SystemInfoDto info = null;
        try {
            info = system.info();
        } catch (RuntimeException e) {
            // shown as "not available"
        }
        List<String> providers = List.of();
        String profileLine = null;
        String cloudLine = null;
        if (info != null) {
            profileLine = t.get("settings.profile.profile", info.profile());
            cloudLine = t.get(info.cloudMode() ? "settings.profile.cloud.yes" : "settings.profile.cloud.no");
            Map<String, String> sorted = info.providers() == null ? Map.of() : new TreeMap<>(info.providers());
            providers = sorted.entrySet().stream()
                    .map(e -> t.get("settings.profile.provider", e.getKey(), e.getValue())).toList();
        }
        return new View(
                options(t, "settings.lang.", s.uiLanguage()),
                options(t, "settings.entryLang.", s.defaultEntryLanguage()),
                Boolean.TRUE.equals(s.keepAudio()), Boolean.TRUE.equals(s.ttsAutoPlay()),
                IntervalsParser.format(s.reviewIntervalsDays()), intervalsError, flash,
                info != null, profileLine, cloudLine, providers, DeleteAllRequest.CONFIRMATION_PHRASE,
                intervalsMessage(t, "settings.intervals.error.range"),
                intervalsMessage(t, "settings.intervals.error.count"));
    }

    private static List<Option> options(Messages t, String keyPrefix, Language selected) {
        Language current = selected == null ? Language.AUTO : selected;
        return List.of(Language.AUTO, Language.DE, Language.EN).stream()
                .map(l -> new Option(l.code(), t.get(keyPrefix + l.code()), l == current)).toList();
    }

    static String intervalsMessage(Messages t, String key) {
        return t.get(key, IntervalsParser.MIN_DAYS, IntervalsParser.MAX_DAYS, IntervalsParser.MAX_COUNT);
    }

    /** Flash text for a known code; anything else (including attacker-supplied text) shows nothing. */
    static Flash flashFor(Messages t, String code) {
        if (code == null) {
            return Flash.NONE;
        }
        return switch (code) {
            case "saved" -> new Flash(t.get("settings.save.done"), false);
            case "nothing" -> new Flash(t.get("settings.save.nothing"), false);
            case "deleted" -> new Flash(t.get("settings.delete.done"), false);
            case "confirmation" -> new Flash(t.get("settings.error.prefix") + " " + t.get("settings.delete.confirmHelp"), true);
            case "deleteFailed", "unreachable" -> new Flash(t.get("settings.error.prefix") + " " + t.get("settings.error.unreachable"), true);
            case "invalid" -> new Flash(t.get("settings.error.prefix") + " " + t.get("memory.error.badInput"), true);
            default -> Flash.NONE;
        };
    }

    private static Language language(String code) {
        try {
            return Language.fromCode(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Response redirect(String path) {
        return Response.seeOther(URI.create(path)).build();
    }
}
