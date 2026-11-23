package dev.rabauer.ai.diary.privacy;

import dev.rabauer.ai.diary.api.privacy.DeleteAllRequest;
import dev.rabauer.ai.diary.api.privacy.DeleteAllResultDto;
import dev.rabauer.ai.diary.rest.PrivacyApi;
import dev.rabauer.ai.diary.web.ApiException;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.time.Clock;
import java.time.LocalDate;

public class PrivacyResource implements PrivacyApi {

    @Inject
    PrivacyService service;
    @Inject
    Clock clock;

    @Override
    public Response export(boolean includeAudio) {
        StreamingOutput body = out -> service.export(out, includeAudio);
        String name = "ai-diary-export-" + LocalDate.now(clock) + ".zip";
        return Response.ok(body, "application/zip")
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .header("Cache-Control", "no-store")
                .build();
    }

    @Override
    public DeleteAllResultDto deleteAll(DeleteAllRequest request) {
        if (request == null || !DeleteAllRequest.CONFIRMATION_PHRASE.equals(request.confirmation())) {
            throw ApiException.badRequest("CONFIRMATION_REQUIRED",
                    "Type " + DeleteAllRequest.CONFIRMATION_PHRASE + " exactly to confirm.");
        }
        try {
            return service.deleteAll();
        } catch (PrivacyService.DeleteIncompleteException e) {
            throw new ApiException(Response.Status.INTERNAL_SERVER_ERROR, "DELETE_INCOMPLETE", e.getMessage());
        }
    }
}
