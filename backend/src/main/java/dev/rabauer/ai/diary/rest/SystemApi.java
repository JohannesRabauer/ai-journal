package dev.rabauer.ai.diary.rest;

import dev.rabauer.ai.diary.api.system.SystemInfoDto;
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

@Path("/api/system")
@Tag(name = "system")
@Produces(MediaType.APPLICATION_JSON)
public interface SystemApi {

    @GET
    @Path("/info")
    @Operation(summary = "Profile, active providers and whether cloud mode (data leaves the device) is active.")
    SystemInfoDto info();
}
