package dev.rabauer.ai.diary.rest;

import dev.rabauer.ai.diary.api.audio.ChunkAckDto;
import dev.rabauer.ai.diary.api.audio.CompleteRecordingRequest;
import dev.rabauer.ai.diary.api.audio.RecordingDto;
import dev.rabauer.ai.diary.api.audio.StartRecordingRequest;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.util.UUID;
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

/**
 * Chunked, resumable audio upload (SPEC section 4.1). Owner: W1a (backend). The browser calls these directly
 * (same origin: the backend also serves the pages, D-033).
 */
@Path("/api/recordings")
@Tag(name = "recordings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface RecordingsApi {

    @POST
    @Operation(summary = "Start a recording; creates a VOICE entry first if entryId is null (save-first).")
    RecordingDto start(StartRecordingRequest request);

    @PUT
    @Path("/{id}/chunks/{index}")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(summary = "Upload one chunk (idempotent per index).")
    @APIResponse(responseCode = "409", description = "Recording already complete")
    ChunkAckDto uploadChunk(@PathParam("id") UUID id, @PathParam("index") int index, InputStream body);

    @GET
    @Path("/{id}")
    @Operation(summary = "Status incl. received chunk indices, used to resume after reload/crash.")
    RecordingDto get(@PathParam("id") UUID id);

    @POST
    @Path("/{id}/complete")
    @Operation(summary = "Declare the final chunk count; assembles audio, sets entry RECORDED and queues transcription.")
    @APIResponse(responseCode = "409", description = "Missing chunks (ErrorDto message lists them)")
    RecordingDto complete(@PathParam("id") UUID id, CompleteRecordingRequest request);

    @GET
    @Path("/{id}/audio")
    @Produces({"audio/webm", "audio/ogg", "audio/mp4", "audio/wav", MediaType.APPLICATION_OCTET_STREAM})
    @Operation(summary = "Stream the assembled audio.")
    Response audio(@PathParam("id") UUID id);

    @DELETE
    @Path("/{id}/audio")
    @Operation(summary = "Delete the audio of a recording; the entry and transcript remain.")
    RecordingDto deleteAudio(@PathParam("id") UUID id);
}
