package dev.rabauer.ai.diary.client;

import dev.rabauer.ai.diary.api.common.ErrorDto;

/** A backend error with its user-presentable {@link ErrorDto}; safe to show in the UI (no journal content). */
public class BackendException extends RuntimeException {

    private final int status;
    private final ErrorDto error;

    public BackendException(int status, ErrorDto error) {
        super(error.code() + ": " + error.message());
        this.status = status;
        this.error = error;
    }

    public int status() {
        return status;
    }

    public ErrorDto error() {
        return error;
    }
}
