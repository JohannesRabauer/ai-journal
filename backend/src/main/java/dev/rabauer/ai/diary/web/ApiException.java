package dev.rabauer.ai.diary.web;

import jakarta.ws.rs.core.Response;

/**
 * Business error mapped to {@link dev.rabauer.ai.diary.api.common.ErrorDto}. The message is shown to the user and
 * MUST NOT contain journal content.
 */
public class ApiException extends RuntimeException {

    private final Response.Status status;
    private final String code;

    public ApiException(Response.Status status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND", what + " not found");
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(Response.Status.CONFLICT, code, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(Response.Status.BAD_REQUEST, code, message);
    }

    public static ApiException notImplemented(String feature) {
        return new ApiException(Response.Status.NOT_IMPLEMENTED, "NOT_IMPLEMENTED", feature + " is not available yet");
    }

    public Response.Status status() {
        return status;
    }

    public String code() {
        return code;
    }
}
