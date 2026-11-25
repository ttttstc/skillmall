package com.iflytek.skillhub.auth.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iflytek.skillhub.auth.gitcode.GitCodeIdentityService;
import com.iflytek.skillhub.auth.gitcode.GitCodeUnavailableException;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class GitCodePassiveSessionAuthenticatorTest {

    private final GitCodeIdentityService identityService = mock(GitCodeIdentityService.class);
    private final GitCodePassiveSessionAuthenticator authenticator =
            new GitCodePassiveSessionAuthenticator(identityService);

    @Test
    void readsOnlyTheFixedAccessTokenCookie() {
        when(identityService.authenticateAccessToken("jwt-token"))
                .thenReturn(Optional.of(principal()));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new jakarta.servlet.http.Cookie("GITCODE_ACCESS_TOKEN", "jwt-token"),
                new jakarta.servlet.http.Cookie("SESSION", "platform-session"));

        assertTrue(authenticator.authenticate(request).isPresent());
        verify(identityService).authenticateAccessToken("jwt-token");
    }

    @Test
    void doesNothingWithoutTheAccessTokenCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("SESSION", "platform-session"));

        assertTrue(authenticator.authenticate(request).isEmpty());
        verify(identityService, never()).authenticateAccessToken(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void toleratesAProviderOutage() {
        when(identityService.authenticateAccessToken("jwt-token"))
                .thenThrow(new GitCodeUnavailableException("down"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("GITCODE_ACCESS_TOKEN", "jwt-token"));

        assertTrue(authenticator.authenticate(request).isEmpty());
        assertEquals("gitcode", authenticator.providerCode());
    }

    private PlatformPrincipal principal() {
        return new PlatformPrincipal("usr-1", "gitcode-user", null, null, "gitcode", Set.of("USER"));
    }
}