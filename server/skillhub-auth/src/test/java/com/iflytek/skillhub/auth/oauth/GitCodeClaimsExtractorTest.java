package com.iflytek.skillhub.auth.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;

class GitCodeClaimsExtractorTest {

    private final GitCodeClaimsExtractor extractor = new GitCodeClaimsExtractor();

    @Test
    void mapsGitCodePayloadToStableClaims() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", "64e5ed8f7e20aa73efcbc302");
        attributes.put("login", "gitcode-xxm");
        attributes.put("name", "xxm");
        attributes.put("avatar_url", "https://cdn-img.gitcode.com/avatar.png");
        attributes.put("email", "xiongjiamu@163.com");

        OAuthClaims claims = extractor.extract(null, user(attributes));

        assertEquals("gitcode", claims.provider());
        assertEquals("64e5ed8f7e20aa73efcbc302", claims.subject());
        assertEquals("gitcode-xxm", claims.providerLogin());
        assertEquals("xiongjiamu@163.com", claims.email());
        // GitCode reports an email without proving ownership, so it never counts as verified.
        assertFalse(claims.emailVerified());
        assertEquals("https://cdn-img.gitcode.com/avatar.png", claims.extra().get("avatar_url"));
    }

    @Test
    void fallsBackToIdWhenLoginIsMissing() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", "64e5ed8f7e20aa73efcbc302");

        OAuthClaims claims = extractor.extract(null, user(attributes));

        assertEquals("64e5ed8f7e20aa73efcbc302", claims.providerLogin());
    }

    @Test
    void rejectsPayloadWithoutStableId() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("login", "gitcode-xxm");

        assertThrows(OAuth2AuthenticationException.class,
                () -> extractor.extract(null, user(attributes)));
    }

    private OAuth2User user(Map<String, Object> attributes) {
        return new OAuth2User() {
            @Override
            public Map<String, Object> getAttributes() {
                return attributes;
            }

            @Override
            public java.util.Collection<? extends org.springframework.security.core.GrantedAuthority> getAuthorities() {
                return java.util.List.of();
            }

            @Override
            public String getName() {
                return String.valueOf(attributes.get("id"));
            }
        };
    }
}
