package dev.rabauer.ai.diary.rest;

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

/** Transcription jobs by id (SPEC section 4.1). Owner: W1a. Entry-scoped routes: {@link EntryTranscriptionsApi}. */
@Path("/api/transcriptions")
@Tag(name = "transcriptions")
@Produces(MediaType.APPLICATION_JSON)
public interface TranscriptionsApi {

    @GET
    @Path("/{id}")
    TranscriptionJobDto get(@PathParam("id") long id);
}
