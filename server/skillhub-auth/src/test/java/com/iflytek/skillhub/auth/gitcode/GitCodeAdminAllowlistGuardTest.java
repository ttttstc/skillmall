package com.iflytek.skillhub.auth.gitcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iflytek.skillhub.auth.entity.IdentityBinding;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.auth.repository.IdentityBindingRepository;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class GitCodeAdminAllowlistGuardTest {

    private final IdentityBindingRepository bindingRepository = mock(IdentityBindingRepository.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final GitCodeAuthProperties properties = new GitCodeAuthProperties();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsAnAllowlistedGitCodeAccount() throws Exception {
        properties.setAdminAllowlist(List.of("alice:SUPER_ADMIN"));
        GitCodeAdminAllowlistGuard guard = new GitCodeAdminAllowlistGuard(properties, bindingRepository);
        authenticateAs("usr-1");
        when(bindingRepository.findByUserId("usr-1"))
                .thenReturn(List.of(new IdentityBinding("usr-1", "gitcode", "64e5ed8f7e20aa73efcbc302", "alice")));

        MockHttpServletRequest request = adminRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        guard.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    void rejectsAnAccountOutsideTheAllowlist() throws Exception {
        properties.setAdminAllowlist(List.of("alice:SUPER_ADMIN"));
        GitCodeAdminAllowlistGuard guard = new GitCodeAdminAllowlistGuard(properties, bindingRepository);
        authenticateAs("usr-2");
        when(bindingRepository.findByUserId("usr-2"))
                .thenReturn(List.of(new IdentityBinding("usr-2", "gitcode", "aaaaaaaaaaaaaaaaaaaaaaaa", "mallory")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        guard.doFilter(adminRequest(), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(403, response.getStatus());
    }

    @Test
    void failsClosedWhenNoAllowlistIsConfigured() throws Exception {
        GitCodeAdminAllowlistGuard guard = new GitCodeAdminAllowlistGuard(properties, bindingRepository);
        authenticateAs("usr-3");
        when(bindingRepository.findByUserId("usr-3"))
                .thenReturn(List.of(new IdentityBinding("usr-3", "gitcode", "64e5ed8f7e20aa73efcbc302", "alice")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        guard.doFilter(adminRequest(), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(403, response.getStatus());
    }

    @Test
    void leavesAnonymousCallersToTheAuthorizationChain() throws Exception {
        properties.setAdminAllowlist(List.of("alice:SUPER_ADMIN"));
        GitCodeAdminAllowlistGuard guard = new GitCodeAdminAllowlistGuard(properties, bindingRepository);

        MockHttpServletRequest request = adminRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        guard.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(bindingRepository, never()).findByUserId(any());
    }

    private void authenticateAs(String userId) {
        PlatformPrincipal principal = new PlatformPrincipal(userId, "gitcode-user", null, null, "gitcode", Set.of("SUPER_ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private MockHttpServletRequest adminRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        request.setServletPath("/api/v1/admin/users");
        return request;
    }
}
