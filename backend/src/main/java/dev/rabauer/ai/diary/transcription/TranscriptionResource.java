package dev.rabauer.ai.diary.transcription;

import dev.rabauer.ai.diary.api.transcription.TranscriptionJobDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * {@code GET /api/transcriptions/{id}} from {@link dev.rabauer.ai.diary.rest.TranscriptionsApi}. Not an implementation
 * of that interface: see {@link EntryTranscriptionResource} for why the interface cannot be implemented as declared.
 */
@Path("/api/transcriptions")
@Produces(MediaType.APPLICATION_JSON)
public class TranscriptionResource {

    @Inject
    TranscriptionService service;

    @GET
    @Path("/{id}")
    public TranscriptionJobDto get(@PathParam("id") long id) {
        return service.get(id);
    }
}
