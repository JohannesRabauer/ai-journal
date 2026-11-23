package dev.rabauer.ai.diary.rest;

import dev.rabauer.ai.diary.api.common.PageDto;
import dev.rabauer.ai.diary.api.entry.CreateEntryRequest;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.UpdateEntryRequest;
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

/** Entries CRUD (SPEC section 4). Save-first: no endpoint here ever calls an LLM. */
@Path("/api/entries")
@Tag(name = "entries")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface EntriesApi {

    @POST
    @Operation(summary = "Create an entry immediately (status DRAFT). No AI processing.")
    EntryDto create(CreateEntryRequest request);

    @GET
    @Operation(summary = "List entries, newest first (entry timestamp desc, id desc).")
    PageDto<EntryDto> list(@QueryParam("page") @DefaultValue("0") int page,
                           @QueryParam("size") @DefaultValue("20") int size);

    @GET
    @Path("/{id}")
    @APIResponse(responseCode = "404", description = "Unknown entry")
    EntryDto get(@PathParam("id") long id);

    @PUT
    @Path("/{id}")
    @Operation(summary = "Autosave / transcript edit. Never triggers AI.")
    EntryDto update(@PathParam("id") long id, UpdateEntryRequest request);

    @POST
    @Path("/{id}/finish")
    @Consumes(MediaType.WILDCARD)
    @Operation(summary = "User marks the entry finished. Triggers background indexing only; the coach is started separately.")
    @APIResponse(responseCode = "409", description = "Entry is still recording or transcribing")
    EntryDto finish(@PathParam("id") long id);

    @DELETE
    @Path("/{id}")
    void delete(@PathParam("id") long id);
}
