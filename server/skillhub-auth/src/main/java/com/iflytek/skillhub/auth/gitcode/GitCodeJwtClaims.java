package com.iflytek.skillhub.auth.gitcode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only view of the claims inside a GitCode access token.
 *
 * <p>GitCode issues HS512 tokens, so the signature cannot be verified without the issuer's
 * secret. These claims are therefore <strong>never</strong> a source of identity: they are used
 * only to skip an obviously expired credential and to cross-check the answer of the user endpoint.
 * A caller who forges a token cannot pass the endpoint check.
 *
 * @param subject {@code sub} claim, normally the GitCode login
 * @param objectId {@code objectId} claim, normally the stable GitCode account id
 * @param expiresAt {@code exp} claim, or null when absent
 */
public record GitCodeJwtClaims(String subject, String objectId, Instant expiresAt) {

    private static final Pattern SUBJECT = Pattern.compile("\"sub\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern OBJECT_ID = Pattern.compile("\"objectId\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern EXPIRES_AT = Pattern.compile("\"exp\"\\s*:\\s*(\\d+)");

    /** Parses the token claims, returning empty for opaque credentials that are not JWTs. */
    public static Optional<GitCodeJwtClaims> parse(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length < 2 || parts[1].isEmpty()) {
            return Optional.empty();
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(pad(parts[1])), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException unreadable) {
            return Optional.empty();
        }
        String subject = firstGroup(SUBJECT, payload);
        String objectId = firstGroup(OBJECT_ID, payload);
        Instant expiresAt = null;
        Matcher exp = EXPIRES_AT.matcher(payload);
        if (exp.find()) {
            try {
                expiresAt = Instant.ofEpochSecond(Long.parseLong(exp.group(1)));
            } catch (NumberFormatException ignored) {
                expiresAt = null;
            }
        }
        if (subject == null && objectId == null && expiresAt == null) {
            return Optional.empty();
        }
        return Optional.of(new GitCodeJwtClaims(subject, objectId, expiresAt));
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    /**
     * Whether the claims agree with the account the user endpoint reported.
     *
     * <p>Any claim that is present must match; a mismatch means the credential and the resolved
     * account disagree, which is treated as an invalid credential.
     */
    public boolean matches(GitCodeIdentity identity) {
        if (identity == null) {
            return false;
        }
        if (objectId != null && !objectId.equalsIgnoreCase(identity.id())) {
            return false;
        }
        if (subject != null && identity.login() != null
                && !subject.equalsIgnoreCase(identity.login())) {
            return false;
        }
        return true;
    }

    private static String firstGroup(Pattern pattern, String payload) {
        Matcher matcher = pattern.matcher(payload);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String pad(String value) {
        int remainder = value.length() % 4;
        if (remainder == 2) {
            return value + "==";
        }
        if (remainder == 3) {
            return value + "=";
        }
        return value;
    }
}
