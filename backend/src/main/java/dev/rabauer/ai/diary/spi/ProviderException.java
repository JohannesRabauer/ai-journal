package dev.rabauer.ai.diary.spi;

/**
 * Failure of an external provider. Messages MUST NOT contain journal content (text, transcripts, prompts).
 */
public class ProviderException extends RuntimeException {

    public enum Kind {
        /** connection refused, DNS failure, timeout: retrying later may help */
        UNREACHABLE,
        /** provider answered with an error status */
        ERROR_RESPONSE,
        /** provider answered but the payload could not be understood */
        BAD_RESPONSE,
        /** the request itself is invalid for this provider (unsupported format, too large, ...) */
        REJECTED
    }

    private final Kind kind;
    private final String provider;

    public ProviderException(String provider, Kind kind, String message, Throwable cause) {
        super("[" + provider + "/" + kind + "] " + message, cause);
        this.kind = kind;
        this.provider = provider;
    }

    public ProviderException(String provider, Kind kind, String message) {
        this(provider, kind, message, null);
    }

    public Kind kind() {
        return kind;
    }

    public String provider() {
        return provider;
    }

    public boolean retryable() {
        return kind == Kind.UNREACHABLE || kind == Kind.ERROR_RESPONSE;
    }
}
