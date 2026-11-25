package com.iflytek.skillhub.auth.oauth;

import java.util.HashMap;
import java.util.Map;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * Normalizes the GitCode user payload from {@code GET /api/v5/user}.
 *
 * <p>The GitCode account id is a stable 24-character hexadecimal identifier and is the only
 * coordinate used as the external subject; the login name may change and is therefore stored as a
 * descriptive attribute only. GitCode reports an email without proving ownership, so this extractor
 * never marks it verified.
 */
@Component
public class GitCodeClaimsExtractor implements OAuthClaimsExtractor {

    private static final String PROVIDER = "gitcode";

    @Override
    public String getProvider() {
        return PROVIDER;
    }

    @Override
    public OAuthClaims extract(OAuth2UserRequest request, OAuth2User oAuth2User) {
        Map<String, Object> attributes = new HashMap<>(oAuth2User.getAttributes());
        String id = text(attributes.get("id"));
        if (id == null) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_user_info", "GitCode user payload has no id", null));
        }
        String login = text(attributes.get("login"));
        if (login == null) {
            login = id;
        }
        return new OAuthClaims(
                PROVIDER,
                id,
                text(attributes.get("email")),
                false,
                login,
                attributes
        );
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
