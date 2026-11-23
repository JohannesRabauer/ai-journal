package dev.rabauer.ai.diary.rest;

import dev.rabauer.ai.diary.api.memory.CandidateDecisionRequest;
import dev.rabauer.ai.diary.api.memory.CandidateStatus;
import dev.rabauer.ai.diary.api.memory.ExtractionRequest;
import dev.rabauer.ai.diary.api.memory.LessonDto;
import dev.rabauer.ai.diary.api.memory.MemoryCandidateDto;
import dev.rabauer.ai.diary.api.memory.MemoryItemDto;
import dev.rabauer.ai.diary.api.memory.ReviewAttemptRequest;
import dev.rabauer.ai.diary.api.memory.ReviewCardDto;
import dev.rabauer.ai.diary.api.memory.ReviewGradeRequest;
import dev.rabauer.ai.diary.api.memory.RevealDto;

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

/** Memory items, retrieval practice and SM-2 reviews (SPEC section 6). Owner: W3a. */
@Path("/api/memory")
@Tag(name = "memory")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface MemoryApi {

    @POST
    @Path("/extractions")
    @Operation(summary = "Ask the LLM for candidate items from an entry/conversation (validated JSON). Nothing becomes a memory item yet.")
    List<MemoryCandidateDto> extract(ExtractionRequest request);

    @GET
    @Path("/candidates")
    List<MemoryCandidateDto> candidates(@QueryParam("status") @DefaultValue("PENDING") CandidateStatus status);

    @POST
    @Path("/candidates/{id}/decision")
    @Operation(summary = "Approve (optionally edited) or discard a candidate. Only APPROVE creates a memory item.")
    MemoryCandidateDto decide(@PathParam("id") long id, CandidateDecisionRequest request);

    @GET
    @Path("/items")
    List<MemoryItemDto> items();

    @DELETE
    @Path("/items/{id}")
    void deleteItem(@PathParam("id") long id);

    @GET
    @Path("/reviews/due")
    @Operation(summary = "Cards due today: prompt only, never the answer.")
    List<ReviewCardDto> due();

    @POST
    @Path("/reviews/{itemId}/attempt")
    @Operation(summary = "Record an attempt; only then is the answer/source revealed.")
    RevealDto attempt(@PathParam("itemId") long itemId, ReviewAttemptRequest request);

    @POST
    @Path("/reviews/{itemId}/grade")
    @APIResponse(responseCode = "409", description = "No attempt recorded for this review")
    MemoryItemDto grade(@PathParam("itemId") long itemId, ReviewGradeRequest request);

    @GET
    @Path("/lessons")
    List<LessonDto> lessons();
}
