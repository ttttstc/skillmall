package com.iflytek.skillhub.gitcode;

/** Raised internally when the GitCode API cannot answer; callers map it to a localized failure. */
public class GitCodeApiException extends RuntimeException {

    public enum Kind {
        /** Repository, ref or path does not exist (HTTP 404). */
        NOT_FOUND,
        /** Provider refused anonymous access (HTTP 401/403). */
        FORBIDDEN,
        /** Anonymous rate limit reached (HTTP 429); the source is fine, the caller is too fast. */
        RATE_LIMITED,
        /** Transport failure, timeout or non-2xx status that is not covered above. */
        UNAVAILABLE,
        /** Response body did not match the documented contract. */
        INVALID_RESPONSE
    }

    private final Kind kind;

    public GitCodeApiException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public GitCodeApiException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
