package dev.rabauer.ai.diary.rest;

import dev.rabauer.ai.diary.api.coach.AnswerRequest;
import dev.rabauer.ai.diary.api.coach.ConversationDto;
import dev.rabauer.ai.diary.api.coach.StartConversationRequest;

import java.util.List;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Coach conversations, stage 2 (SPEC section 5). Owner: W2a. */
@Path("/api")
@Tag(name = "coach")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface CoachApi {

    @POST
    @Path("/conversations")
    @Operation(summary = "Start a conversation for a FINISHED entry; returns it with 2-3 opening questions.")
    @APIResponse(responseCode = "409", description = "Entry not finished")
    ConversationDto start(StartConversationRequest request);

    @GET
    @Path("/conversations/{id}")
    ConversationDto get(@PathParam("id") long id);

    @GET
    @Path("/entries/{entryId}/conversations")
    List<ConversationDto> forEntry(@PathParam("entryId") long entryId);

    @POST
    @Path("/conversations/{id}/answers")
    @Operation(summary = "Add the user's answer; the coach replies with a follow-up question (or an explanation only if explicitly asked).")
    @APIResponse(responseCode = "409", description = "Conversation paused or ended")
    ConversationDto answer(@PathParam("id") long id, AnswerRequest request);

    @POST
    @Path("/conversations/{id}/pause")
    @Consumes(MediaType.WILDCARD)
    ConversationDto pause(@PathParam("id") long id);

    @POST
    @Path("/conversations/{id}/resume")
    @Consumes(MediaType.WILDCARD)
    ConversationDto resume(@PathParam("id") long id);

    @POST
    @Path("/conversations/{id}/end")
    @Consumes(MediaType.WILDCARD)
    ConversationDto end(@PathParam("id") long id);
}
