package dev.rabauer.ai.diary.transcription;

import dev.rabauer.ai.diary.api.transcription.StartTranscriptionRequest;
import dev.rabauer.ai.diary.api.transcription.TranscriptionJobDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Serves {@code POST /api/entries/{entryId}/transcriptions} and {@code GET .../latest}, the routes declared in
 * {@link dev.rabauer.ai.diary.rest.TranscriptionsApi}.
 *
 * <p>{@code TranscriptionsApi} cannot be implemented as declared: it is rooted at {@code /api}, but Quarkus REST picks
 * the resource class by its class-level path first, and {@code EntriesApi} owns the prefix {@code /api/entries}, so
 * those routes answer 404 (verified). Registering them again here is rejected as a duplicate route, so this class and
 * {@code TranscriptionResource} serve the same URLs and DTOs without implementing the interface. Same request and
 * response shapes; the orchestrator should fix the contract (see report) before other workers hit the same problem.
 */
@Path("/api/entries/{entryId}/transcriptions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EntryTranscriptionResource {

    @Inject
    TranscriptionService service;

    @POST
    public TranscriptionJobDto start(@PathParam("entryId") long entryId, StartTranscriptionRequest request) {
        return service.start(entryId, request);
    }

    @GET
    @Path("/latest")
    public TranscriptionJobDto latest(@PathParam("entryId") long entryId) {
        return service.latest(entryId);
    }
}
