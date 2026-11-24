package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.memory.CandidateDecisionRequest;
import dev.rabauer.ai.diary.api.memory.CandidateStatus;
import dev.rabauer.ai.diary.api.memory.ExtractionRequest;
import dev.rabauer.ai.diary.api.memory.LessonDto;
import dev.rabauer.ai.diary.api.memory.MemoryCandidateDto;
import dev.rabauer.ai.diary.api.memory.MemoryItemDto;
import dev.rabauer.ai.diary.api.memory.MemoryItemType;
import dev.rabauer.ai.diary.api.memory.ReviewCardDto;
import dev.rabauer.ai.diary.memory.MemoryService;
import dev.rabauer.ai.diary.security.Csrf;
import dev.rabauer.ai.diary.web.ui.MemoryPresenter.Target;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Remember (SPEC sections 6 and 12): retrieval practice, suggestions to approve, edit or discard, the user's cards and
 * the technique lessons. No scores, percentages or streaks, only "due today: n".
 * <p>
 * The page renders every part on the server. Reviewing cards needs {@code js/memory.js} (one card at a time, attempt
 * first, grade with next-interval hints); the suggestions and the card list also work without JavaScript through
 * form posts with the form token ({@code /memory/candidates/{id}/approve|discard}, {@code /memory/items/{id}/delete},
 * {@code /memory/extract}) that redirect back with a short message code. Extraction only ever starts from an explicit
 * user action: the JS path asks the JSON API, the no-JS path posts a form; opening {@code ?source=&id=} alone changes
 * nothing.
 */
@Path("/memory")
@Produces(MediaType.TEXT_HTML)
public class MemoryPage {

    private static final Set<String> MESSAGES = Set.of("approved", "discarded", "itemDeleted", "extractNone", "extracted");
    private static final Set<String> ERRORS = Set.of("unreachable", "invalid", "provider", "state", "notFound", "badInput",
            "unavailable", "notFinished", "empty");
    private static final Set<String> PROBLEMS = Set.of("promptEmpty", "promptTooLong", "answerEmpty", "answerTooLong");
    private static final Set<String> PANELS = Set.of("candidates", "items", "lessons");

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance page(Shell shell, Messages t, View v);
    }

    public record Card(long id, String type, String typeLabel, String prompt) {
    }

    public record TypeOption(String value, String label, boolean selected) {
    }

    public record Candidate(long id, String typeLabel, String prompt, String answer, String quote, boolean editing,
                            List<TypeOption> types) {
    }

    public record Item(long id, String typeLabel, String prompt, String next, String deleteLabel) {
    }

    public record Lesson(String title, String body, boolean unlocked, String hint) {
    }

    public record Tile(String panel, String title, String description, boolean open, String href, String sectionId) {
    }

    /**
     * @param extractSource {@code entry}/{@code conversation}, empty when the page was not opened for an extraction
     * @param problem       message under the edit form (empty when none)
     */
    public record View(String eyebrow, int dueCount, List<Card> due, Flash flash, List<Tile> tiles, String openPanel,
                       List<Candidate> candidates, String candidatesIntro, List<Item> items, List<Lesson> lessons,
                       String extractSource, long extractId, String problem, String dueOne, String dueMany, String dueNone) {
    }

    @Inject
    MemoryService memory;
    @Inject
    ShellFactory shells;
    @Inject
    Csrf csrf;

    @GET
    public TemplateInstance page(@QueryParam("panel") String panelParam, @QueryParam("edit") Long editId,
                                 @QueryParam("source") String source, @QueryParam("id") String idParam,
                                 @QueryParam("msg") String msg, @QueryParam("n") Integer created,
                                 @QueryParam("error") String error, @QueryParam("problem") String problem,
                                 @Context HttpHeaders headers) {
        Messages t = Messages.forAcceptable(headers.getAcceptableLanguages());
        Locale locale = t.locale();
        Flash flash = Flash.NONE;
        if (error != null && ERRORS.contains(error)) {
            flash = new Flash(t.get("memory.error." + error), true);
        } else if (msg != null && MESSAGES.contains(msg)) {
            flash = new Flash(messageText(t, msg, created), false);
        }

        List<ReviewCardDto> due = List.of();
        List<MemoryCandidateDto> candidates = List.of();
        List<MemoryItemDto> items = List.of();
        List<LessonDto> lessons = List.of();
        try {
            due = memory.due();
            candidates = memory.candidates(CandidateStatus.PENDING);
            items = memory.items();
            lessons = memory.lessons("de".equals(t.lang()));
        } catch (RuntimeException e) {
            flash = new Flash(t.get(MemoryPresenter.errorFor(e).messageKey()), true);
        }

        Target target = MemoryPresenter.targetFrom(Map.of(
                Target.PARAM_SOURCE, source == null ? List.of() : List.of(source),
                Target.PARAM_ID, idParam == null ? List.of() : List.of(idParam))).orElse(null);
        String open = panelParam != null && PANELS.contains(panelParam) ? panelParam : "";
        if (editId != null) {
            open = "candidates";
        }
        if (target != null && open.isEmpty()) {
            open = "candidates";
        }

        List<Card> cards = due.stream().map(c -> new Card(c.itemId(), c.type().name(),
                t.get(MemoryPresenter.typeKey(c.type())), c.prompt())).toList();
        List<TypeOption> allTypes = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);

        List<Candidate> cs = new ArrayList<>();
        for (MemoryCandidateDto c : candidates) {
            boolean editing = editId != null && editId == c.id();
            cs.add(new Candidate(c.id(), t.get(MemoryPresenter.typeKey(c.type())), c.prompt(), c.answer(), c.sourceQuote(),
                    editing, typeOptions(t, c.type())));
        }
        List<Item> is = items.stream().map(i -> new Item(i.id(), t.get(MemoryPresenter.typeKey(i.type())), i.prompt(),
                t.get("memory.items.next", i.nextReviewOn().format(fmt)), t.get("memory.items.delete.label", i.prompt()))).toList();
        List<Lesson> ls = lessons.stream().map(l -> new Lesson(l.title(), l.body(), l.unlocked(),
                l.unlockHint() == null ? "" : l.unlockHint())).toList();

        List<Tile> tiles = List.of(
                tile(t, "candidates", t.get("memory.candidates.title") + " · " + candidates.size(), "memory.tile.candidates", open),
                tile(t, "items", t.get("memory.items.title") + " · " + items.size(), "memory.tile.items", open),
                tile(t, "lessons", t.get("memory.lessons.title"), "memory.lessons.intro", open));

        String problemText = problem != null && PROBLEMS.contains(problem) ? t.get("memory.edit." + problem) : "";
        View v = new View(due.isEmpty() ? t.get("memory.due.none") : t.get("memory.due.title", due.size()), due.size(), cards,
                flash, tiles, open, cs, t.get(candidates.isEmpty() ? "memory.candidates.none" : "memory.candidates.intro"),
                is, ls, target == null ? "" : target.type().name().toLowerCase(Locale.ROOT), target == null ? 0 : target.id(),
                problemText, t.get("memory.due.title", 1), t.get("memory.due.title", 2).replace("2", "{0}"), t.get("memory.due.none"));
        return Templates.page(shells.create(t, "/memory"), t, v);
    }

    private static Tile tile(Messages t, String panel, String title, String descKey, String open) {
        boolean isOpen = panel.equals(open);
        return new Tile(panel, title, t.get(descKey), isOpen, isOpen ? "/memory" : "/memory?panel=" + panel + "#memory-panel-" + panel,
                "memory-panel-" + panel);
    }

    private static List<TypeOption> typeOptions(Messages t, MemoryItemType selected) {
        return java.util.Arrays.stream(MemoryItemType.values())
                .map(type -> new TypeOption(type.name(), t.get(MemoryPresenter.typeKey(type)), type == selected)).toList();
    }

    private static String messageText(Messages t, String code, Integer created) {
        return switch (code) {
            case "approved" -> t.get("memory.candidate.approved");
            case "discarded" -> t.get("memory.candidate.discarded");
            case "itemDeleted" -> t.get("memory.items.deleted");
            case "extracted" -> t.get("memory.extract.done", created == null ? 1 : Math.max(1, Math.min(created, 99)));
            default -> t.get("memory.extract.none");
        };
    }

    // ------------------------------------------------------------------ no-JavaScript writes

    /** Keep a suggestion; the edit form also posts prompt, answer and type, a plain "Keep" posts nothing else. */
    @POST
    @Path("candidates/{id}/approve")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response approve(@PathParam("id") long id, @FormParam("prompt") String prompt, @FormParam("answer") String answer,
                            @FormParam("type") String type, @FormParam("_csrf") String token) {
        csrf.require(token);
        boolean edited = prompt != null || answer != null;
        if (edited) {
            var problem = MemoryPresenter.editProblem(prompt, answer);
            if (problem.isPresent()) {
                return redirect("/memory?edit=" + id + "&problem=" + problem.get().substring("memory.edit.".length()));
            }
        }
        try {
            memory.decide(id, new CandidateDecisionRequest(CandidateDecisionRequest.Decision.APPROVE,
                    edited ? prompt.strip() : null, edited ? answer.strip() : null, itemType(type)));
            return redirect("/memory?panel=candidates&msg=approved");
        } catch (RuntimeException e) {
            return redirect("/memory?panel=candidates&error=" + errorCode(e));
        }
    }

    @POST
    @Path("candidates/{id}/discard")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response discard(@PathParam("id") long id, @FormParam("_csrf") String token) {
        csrf.require(token);
        try {
            memory.decide(id, new CandidateDecisionRequest(CandidateDecisionRequest.Decision.DISCARD, null, null, null));
            return redirect("/memory?panel=candidates&msg=discarded");
        } catch (RuntimeException e) {
            return redirect("/memory?panel=candidates&error=" + errorCode(e));
        }
    }

    @POST
    @Path("items/{id}/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response deleteItem(@PathParam("id") long id, @FormParam("_csrf") String token) {
        csrf.require(token);
        try {
            memory.deleteItem(id);
            return redirect("/memory?panel=items&msg=itemDeleted");
        } catch (RuntimeException e) {
            return redirect("/memory?panel=items&error=" + errorCode(e));
        }
    }

    /** "Extract learnings" without JavaScript: only an explicit post asks the language model. */
    @POST
    @Path("extract")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response extract(@FormParam("source") String source, @FormParam("id") String id, @FormParam("_csrf") String token) {
        csrf.require(token);
        var target = MemoryPresenter.targetFrom(Map.of(Target.PARAM_SOURCE, source == null ? List.of() : List.of(source),
                Target.PARAM_ID, id == null ? List.of() : List.of(id)));
        if (target.isEmpty()) {
            return redirect("/memory?error=badInput");
        }
        try {
            int n = memory.extract(new ExtractionRequest(target.get().type(), target.get().id())).size();
            return redirect(n == 0 ? "/memory?panel=candidates&msg=extractNone" : "/memory?panel=candidates&msg=extracted&n=" + n);
        } catch (RuntimeException e) {
            return redirect("/memory?error=" + errorCode(e));
        }
    }

    static String errorCode(RuntimeException e) {
        String code = MemoryPresenter.errorFor(e).messageKey().substring("memory.error.".length());
        return ERRORS.contains(code) ? code : "unreachable";
    }

    private static MemoryItemType itemType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return MemoryItemType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Response redirect(String path) {
        return Response.seeOther(URI.create(path)).build();
    }
}
