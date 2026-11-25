package com.iflytek.skillhub.auth.gitcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iflytek.skillhub.auth.policy.RouteSecurityPolicyRegistry;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;

class GitCodeCredentialAuthenticationFilterTest {

    private final GitCodeIdentityService identityService = mock(GitCodeIdentityService.class);
    private final AuthenticationEntryPoint entryPoint = mock(AuthenticationEntryPoint.class);
    private final RouteSecurityPolicyRegistry registry = mock(RouteSecurityPolicyRegistry.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final GitCodeCredentialAuthenticationFilter filter =
            new GitCodeCredentialAuthenticationFilter(identityService, entryPoint, registry);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesABearerToken() throws Exception {
        when(identityService.authenticateAccessToken("valid")).thenReturn(Optional.of(principal()));
        MockHttpServletRequest request = apiRequest();
        request.addHeader("Authorization", "Bearer valid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals("usr-1", principalOf().userId());
    }

    @Test
    void rejectsAnInvalidBearerToken() throws Exception {
        when(identityService.authenticateAccessToken("bad")).thenReturn(Optional.empty());
        MockHttpServletRequest request = apiRequest();
        request.addHeader("Authorization", "Bearer bad");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(entryPoint).commence(eq(request), eq(response), any());
        verify(chain, never()).doFilter(any(), any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void authenticatesTheFixedCookie() throws Exception {
        when(identityService.authenticateAccessToken("jwt")).thenReturn(Optional.of(principal()));
        MockHttpServletRequest request = apiRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("GITCODE_ACCESS_TOKEN", "jwt"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals("usr-1", principalOf().userId());
    }

    @Test
    void invalidCookieFallsBackToAnonymous() throws Exception {
        when(identityService.authenticateAccessToken("stale")).thenReturn(Optional.empty());
        MockHttpServletRequest request = apiRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("GITCODE_ACCESS_TOKEN", "stale"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        // A stale browser cookie must not turn every page into an error.
        verify(chain).doFilter(request, response);
        verify(entryPoint, never()).commence(any(), any(), any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void providerOutageIs503OnProtectedRoutes() throws Exception {
        when(identityService.authenticateAccessToken("jwt")).thenThrow(new GitCodeUnavailableException("down"));
        when(registry.accessLevel("GET", "/api/v1/me/skills"))
                .thenReturn(RouteSecurityPolicyRegistry.AccessLevel.AUTHENTICATED);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me/skills");
        request.setServletPath("/api/v1/me/skills");
        request.setCookies(new jakarta.servlet.http.Cookie("GITCODE_ACCESS_TOKEN", "jwt"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void providerOutageIsAnonymousOnPublicRoutes() throws Exception {
        when(identityService.authenticateAccessToken("jwt")).thenThrow(new GitCodeUnavailableException("down"));
        when(registry.accessLevel("GET", "/api/v1/search"))
                .thenReturn(RouteSecurityPolicyRegistry.AccessLevel.PERMIT_ALL);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/search");
        request.setServletPath("/api/v1/search");
        request.setCookies(new jakarta.servlet.http.Cookie("GITCODE_ACCESS_TOKEN", "jwt"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    void ignoresRequestsWithoutAnyCredential() throws Exception {
        MockHttpServletRequest request = apiRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(identityService, never()).authenticateAccessToken(any());
        verify(chain).doFilter(request, response);
    }

    private PlatformPrincipal principalOf() {
        return (PlatformPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private PlatformPrincipal principal() {
        return new PlatformPrincipal("usr-1", "gitcode-user", null, null, "gitcode", Set.of("USER"));
    }

    private MockHttpServletRequest apiRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/providers");
        request.setServletPath("/api/v1/auth/providers");
        return request;
    }
}