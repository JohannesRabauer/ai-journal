package dev.rabauer.ai.diary.rest;

import dev.rabauer.ai.diary.api.tts.SpeakRequest;
import jakarta.ws.rs.core.Response;
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

/** Text to speech (SPEC section 9). Owner: W2b. */
@Path("/api/tts")
@Tag(name = "tts")
public interface TtsApi {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({"audio/wav", "audio/mpeg", MediaType.APPLICATION_JSON})
    @Operation(summary = "Synthesize speech (cached). 204 when TTS is disabled or failing: the client shows text only.")
    Response speak(SpeakRequest request);

    @GET
    @Path("/voices")
    @Produces(MediaType.APPLICATION_JSON)
    java.util.List<dev.rabauer.ai.diary.api.tts.VoiceDto> voices();
}
