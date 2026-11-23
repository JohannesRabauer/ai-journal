package dev.rabauer.ai.diary.web;

import dev.rabauer.ai.diary.api.common.ErrorDto;
import dev.rabauer.ai.diary.llm.InvalidLlmOutputException;
import dev.rabauer.ai.diary.spi.ProviderException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Maps exceptions to {@link ErrorDto}. Never echoes request bodies or journal content. */
public class ErrorMappers {

    private static final Logger LOG = Logger.getLogger(ErrorMappers.class);

    @ServerExceptionMapper
    public Response api(ApiException e) {
        return json(e.status().getStatusCode(), new ErrorDto(e.code(), e.getMessage()));
    }

    @ServerExceptionMapper
    public Response provider(ProviderException e) {
        LOG.warnf("Provider failure: %s/%s", e.provider(), e.kind());
        String code = e.kind() == ProviderException.Kind.UNREACHABLE ? "PROVIDER_UNREACHABLE" : "PROVIDER_ERROR";
        return json(503, new ErrorDto(code, "The AI service is not available right now. Your data is saved; please try again later."));
    }

    @ServerExceptionMapper
    public Response invalidLlm(InvalidLlmOutputException e) {
        return json(502, new ErrorDto("LLM_OUTPUT_INVALID", "The AI gave an unusable answer. Nothing was saved; please try again."));
    }

    private static Response json(int status, ErrorDto dto) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(dto).build();
    }
}
