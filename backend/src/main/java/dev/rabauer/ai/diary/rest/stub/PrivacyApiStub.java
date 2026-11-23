package dev.rabauer.ai.diary.rest.stub;

import dev.rabauer.ai.diary.api.privacy.DeleteAllRequest;
import dev.rabauer.ai.diary.api.privacy.DeleteAllResultDto;
import jakarta.ws.rs.core.Response;
import dev.rabauer.ai.diary.rest.PrivacyApi;
import dev.rabauer.ai.diary.web.ApiException;

/**
 * Placeholder returning 501 until the owning worker implements {@link PrivacyApi}. DELETE this class in the same
 * commit that adds the real implementation.
 */
public class PrivacyApiStub implements PrivacyApi {
    @Override
    public Response export(boolean includeAudio) {
        throw ApiException.notImplemented("Export and delete");
    }

    @Override
    public DeleteAllResultDto deleteAll(DeleteAllRequest request) {
        throw ApiException.notImplemented("Export and delete");
    }
}
