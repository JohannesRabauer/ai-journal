package dev.rabauer.ai.diary.rest;

import dev.rabauer.ai.diary.api.gym.AttemptDto;
import dev.rabauer.ai.diary.api.gym.AttemptRequest;
import dev.rabauer.ai.diary.api.gym.ExerciseDto;
import dev.rabauer.ai.diary.api.gym.GymCategory;

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

/** Thinking Gym (SPEC section 7). Owner: W3b. Must work with no LLM available. */
@Path("/api/gym")
@Tag(name = "gym")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface GymApi {

    @POST
    @Path("/exercises")
    @Consumes(MediaType.WILDCARD)
    @Operation(summary = "Get the next exercise (optionally for a category); personalized if an LLM is available, else a template.")
    ExerciseDto next(@QueryParam("category") GymCategory category);

    @GET
    @Path("/exercises/{id}")
    ExerciseDto get(@PathParam("id") long id);

    @POST
    @Path("/exercises/{id}/attempts")
    AttemptDto attempt(@PathParam("id") long id, AttemptRequest request);

    @POST
    @Path("/attempts/{attemptId}/feedback")
    @Consumes(MediaType.WILDCARD)
    @Operation(summary = "Optional: AI follow-up questions on an attempt, only on request.")
    AttemptDto feedback(@PathParam("attemptId") long attemptId);

    @GET
    @Path("/attempts")
    List<AttemptDto> history(@QueryParam("category") GymCategory category);
}
