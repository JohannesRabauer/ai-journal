package dev.rabauer.ai.diary.rest;

import dev.rabauer.ai.diary.api.privacy.DeleteAllRequest;
import dev.rabauer.ai.diary.api.privacy.DeleteAllResultDto;
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

/** Export and delete-everything (SPEC section 10). Owner: W1d. Iterates all UserDataOwner beans. */
@Path("/api/data")
@Tag(name = "privacy")
public interface PrivacyApi {

    @GET
    @Path("/export")
    @Produces("application/zip")
    @Operation(summary = "Zip with JSON of all user data; audio included when includeAudio=true.")
    Response export(@QueryParam("includeAudio") @DefaultValue("false") boolean includeAudio);

    @POST
    @Path("/delete-all")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "400", description = "Confirmation phrase missing or wrong")
    DeleteAllResultDto deleteAll(DeleteAllRequest request);
}
