package com.iflytek.skillhub.auth.gitcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GitCodeJwtClaimsTest {

    private static final GitCodeIdentity IDENTITY =
            new GitCodeIdentity("6afffb36ebcfc5505fd571f0", "ttttstc", "ttttstc", null, null);

    @Test
    void readsSubjectObjectIdAndExpiry() {
        String token = token("ttttstc", "6afffb36ebcfc5505fd571f0", Instant.now().plusSeconds(600));

        GitCodeJwtClaims claims = GitCodeJwtClaims.parse(token).orElseThrow();

        assertEquals("ttttstc", claims.subject());
        assertEquals("6afffb36ebcfc5505fd571f0", claims.objectId());
        assertFalse(claims.isExpired(Instant.now()));
        assertTrue(claims.matches(IDENTITY));
    }

    @Test
    void detectsExpiredTokens() {
        String token = token("ttttstc", "6afffb36ebcfc5505fd571f0", Instant.now().minusSeconds(60));

        assertTrue(GitCodeJwtClaims.parse(token).orElseThrow().isExpired(Instant.now()));
    }

    @Test
    void rejectsClaimsThatDisagreeWithTheResolvedAccount() {
        String otherAccount = token("someone-else", "aaaaaaaaaaaaaaaaaaaaaaaa", Instant.now().plusSeconds(600));
        assertFalse(GitCodeJwtClaims.parse(otherAccount).orElseThrow().matches(IDENTITY));

        String otherLogin = token("someone-else", "6afffb36ebcfc5505fd571f0", Instant.now().plusSeconds(600));
        assertFalse(GitCodeJwtClaims.parse(otherLogin).orElseThrow().matches(IDENTITY));
    }

    @Test
    void treatsOpaqueCredentialsAsNonJwt() {
        assertTrue(GitCodeJwtClaims.parse("glpat-0123456789abcdef").isEmpty());
        assertTrue(GitCodeJwtClaims.parse(null).isEmpty());
    }

    private String token(String subject, String objectId, Instant expiresAt) {
        String payload = "{\"sub\":\"" + subject + "\",\"objectId\":\"" + objectId
                + "\",\"exp\":" + expiresAt.getEpochSecond() + "}";
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return "eyJhbGciOiJIUzUxMiJ9." + encoded + ".signature-not-verified";
    }
}