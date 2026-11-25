package com.iflytek.skillhub.auth.bootstrap;

import com.iflytek.skillhub.auth.gitcode.GitCodeCredentialAuthenticationFilter;
import com.iflytek.skillhub.auth.gitcode.GitCodeIdentityService;
import com.iflytek.skillhub.auth.gitcode.GitCodeUnavailableException;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Establishes a SkillHub session from the GitCode access token cookie already on the request.
 *
 * <p>Only the fixed {@code GITCODE_ACCESS_TOKEN} cookie is read; the token is verified against the
 * same GitCode user endpoint as an explicit bearer credential. The authenticator never redirects and
 * never creates a session itself: {@code POST /api/v1/auth/session/bootstrap} owns that decision.
 */
@Component
public class GitCodePassiveSessionAuthenticator implements PassiveSessionAuthenticator {

    private final GitCodeIdentityService identityService;

    public GitCodePassiveSessionAuthenticator(GitCodeIdentityService identityService) {
        this.identityService = identityService;
    }

    @Override
    public String providerCode() {
        return GitCodeIdentityService.PROVIDER_CODE;
    }

    @Override
    public String displayName() {
        return "GitCode";
    }

    @Override
    public Optional<PlatformPrincipal> authenticate(HttpServletRequest request) {
        String accessToken = accessTokenCookie(request);
        if (accessToken == null) {
            return Optional.empty();
        }
        try {
            return identityService.authenticateAccessToken(accessToken);
        } catch (GitCodeUnavailableException unavailable) {
            // A provider outage must not fail the bootstrap with a server error; it simply means no
            // passive identity is available and the caller can fall back to an explicit login.
            return Optional.empty();
        }
    }

    private String accessTokenCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (GitCodeCredentialAuthenticationFilter.ACCESS_TOKEN_COOKIE.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }
}