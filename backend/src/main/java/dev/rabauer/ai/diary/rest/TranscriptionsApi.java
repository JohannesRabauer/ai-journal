package dev.rabauer.ai.diary.rest;

import dev.rabauer.ai.diary.api.transcription.StartTranscriptionRequest;
import dev.rabauer.ai.diary.api.transcription.TranscriptionJobDto;
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

/** Transcription jobs (SPEC section 4.1). Owner: W1a. */
@Path("/api")
@Tag(name = "transcriptions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface TranscriptionsApi {

    @POST
    @Path("/entries/{entryId}/transcriptions")
    @Operation(summary = "Start or retry transcription of the entry's recording (async).")
    @APIResponse(responseCode = "409", description = "No complete recording, or a job is already running")
    TranscriptionJobDto start(@PathParam("entryId") long entryId, StartTranscriptionRequest request);

    @GET
    @Path("/entries/{entryId}/transcriptions/latest")
    @APIResponse(responseCode = "404", description = "No job yet")
    TranscriptionJobDto latest(@PathParam("entryId") long entryId);

    @GET
    @Path("/transcriptions/{id}")
    TranscriptionJobDto get(@PathParam("id") long id);
}
