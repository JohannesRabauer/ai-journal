package dev.rabauer.ai.diary.rest;

import dev.rabauer.ai.diary.api.insights.GenerateWeeklyReportRequest;
import dev.rabauer.ai.diary.api.insights.WeeklyReportDto;
import dev.rabauer.ai.diary.api.insights.WeeklyReportSummaryDto;

import java.util.List;
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

/** Weekly reflection (SPEC section 8). Owner: W3c. */
@Path("/api/weekly-reports")
@Tag(name = "weekly-reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface WeeklyReportsApi {

    @POST
    @Operation(summary = "Generate (or regenerate) the report for an ISO week from finished entries (validated JSON).")
    WeeklyReportDto generate(GenerateWeeklyReportRequest request);

    @GET
    List<WeeklyReportSummaryDto> list();

    @GET
    @Path("/{id}")
    WeeklyReportDto get(@PathParam("id") long id);

    @PUT
    @Path("/{id}")
    @Operation(summary = "User edits of a report.")
    WeeklyReportDto update(@PathParam("id") long id, WeeklyReportDto report);

    @DELETE
    @Path("/{id}")
    void delete(@PathParam("id") long id);
}
