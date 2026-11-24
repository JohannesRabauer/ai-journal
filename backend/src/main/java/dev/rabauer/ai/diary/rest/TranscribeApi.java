package dev.rabauer.ai.diary.rest;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.transcription.TranscribeResultDto;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.io.InputStream;

/**
 * Synchronous transcribe-only endpoint for short audio (spoken coach answers, max ~2 minutes / 10 MB).
 * Uses the configured SpeechToText; persists nothing and never logs the text. Owner: W2a.
 */
@Path("/api/transcribe")
@Tag(name = "transcriptions")
public interface TranscribeApi {

    @POST
    @Consumes({"audio/webm", "audio/ogg", "audio/mp4", "audio/wav", MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Transcribe a short audio clip without storing it.")
    @APIResponse(responseCode = "413", description = "Audio too large")
    @APIResponse(responseCode = "503", description = "Speech-to-text unavailable (ErrorDto); the client offers typing instead")
    TranscribeResultDto transcribe(@QueryParam("language") @DefaultValue("AUTO") Language language,
                                   @HeaderParam("Content-Type") String contentType,
                                   InputStream audio);
}
