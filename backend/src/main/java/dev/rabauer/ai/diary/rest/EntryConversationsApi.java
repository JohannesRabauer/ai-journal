package dev.rabauer.ai.diary.rest;

import dev.rabauer.ai.diary.api.coach.ConversationDto;

import java.util.List;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/*
 * Contract rule: JAX-RS selects the resource class by its class-level @Path first, so a class rooted at /api cannot
 * serve /api/entries/... next to EntriesApi. Entry-scoped routes get their own interface rooted at
 * /api/entries/{entryId}/... (found by W1a).
 */

/** Coach conversations of one entry. Owner: W2a. */
@Path("/api/entries/{entryId}/conversations")
@Tag(name = "coach")
@Produces(MediaType.APPLICATION_JSON)
public interface EntryConversationsApi {

    @GET
    List<ConversationDto> forEntry(@PathParam("entryId") long entryId);
}
