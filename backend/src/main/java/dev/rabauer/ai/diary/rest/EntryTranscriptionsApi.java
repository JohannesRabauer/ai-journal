package dev.rabauer.ai.diary.rest;

import dev.rabauer.ai.diary.api.transcription.StartTranscriptionRequest;
import dev.rabauer.ai.diary.api.transcription.TranscriptionJobDto;
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

/** Transcription of one entry's recording (SPEC section 4.1). Owner: W1a. */
@Path("/api/entries/{entryId}/transcriptions")
@Tag(name = "transcriptions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface EntryTranscriptionsApi {

    @POST
    @Operation(summary = "Start or retry transcription of the entry's recording (async).")
    @APIResponse(responseCode = "409", description = "No complete recording, or a job is already running")
    TranscriptionJobDto start(@PathParam("entryId") long entryId, StartTranscriptionRequest request);

    @GET
    @Path("/latest")
    @APIResponse(responseCode = "404", description = "No job yet")
    TranscriptionJobDto latest(@PathParam("entryId") long entryId);
}
