package com.iflytek.skillhub.auth.gitcode;

/**
 * Signals that GitCode could not be reached or answered with a transient failure.
 *
 * <p>Callers must translate this into an availability response instead of treating the caller as
 * unauthenticated, so that a provider outage does not silently look like an invalid credential.
 */
public class GitCodeUnavailableException extends RuntimeException {

    public GitCodeUnavailableException(String message) {
        super(message);
    }

    public GitCodeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
