package com.iflytek.skillhub.auth.gitcode;

import com.iflytek.skillhub.auth.policy.RouteSecurityPolicyRegistry;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates API callers with a GitCode credential.
 *
 * <p>Two sources are accepted and both are verified against the same GitCode user endpoint:
 * an explicit {@code Authorization: Bearer <token>} credential, and the {@code GITCODE_ACCESS_TOKEN}
 * cookie that the GitCode web session already carries on a same-parent-domain deployment.
 *
 * <p>Semantics differ on purpose. An explicit bearer credential that GitCode rejects is answered
 * with 401 and never falls back to anonymous, because the caller clearly intended to authenticate.
 * A rejected or expired cookie is treated as anonymous so public pages keep working; only routes
 * that require an identity end up with 401. A GitCode outage is reported as 503 on protected
 * routes and silently ignored on public ones.
 */
public class GitCodeCredentialAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GitCodeCredentialAuthenticationFilter.class);

    /** Fixed cookie name; there is no configurable cookie allowlist. */
    public static final String ACCESS_TOKEN_COOKIE = "GITCODE_ACCESS_TOKEN";

    /** Lets a cookie-authenticated client opt out of CSRF for state-changing calls. */
    public static final String COOKIE_AUTH_HEADER = "X-GitCode-Cookie-Auth";

    private static final String BEARER = "Bearer ";

    private final GitCodeIdentityService identityService;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final RouteSecurityPolicyRegistry routeSecurityPolicyRegistry;

    public GitCodeCredentialAuthenticationFilter(GitCodeIdentityService identityService,
                                                 AuthenticationEntryPoint authenticationEntryPoint,
                                                 RouteSecurityPolicyRegistry routeSecurityPolicyRegistry) {
        this.identityService = identityService;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.routeSecurityPolicyRegistry = routeSecurityPolicyRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }
        String bearer = bearerCredential(request);
        if (bearer != null) {
            authenticateBearer(request, response, filterChain, bearer);
            return;
        }
        String cookieCredential = cookieCredential(request);
        if (cookieCredential != null) {
            authenticateCookie(request, response, filterChain, cookieCredential);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void authenticateBearer(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain, String credential)
            throws ServletException, IOException {
        if (credential.isEmpty()) {
            reject(request, response);
            return;
        }
        Optional<PlatformPrincipal> principal;
        try {
            principal = identityService.authenticateAccessToken(credential);
        } catch (GitCodeUnavailableException unavailable) {
            log.warn("GitCode identity lookup is unavailable; rejecting the API request");
            unavailable(response);
            return;
        }
        if (principal.isEmpty()) {
            reject(request, response);
            return;
        }
        applyAuthentication(principal.get());
        filterChain.doFilter(request, response);
    }

    private void authenticateCookie(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain, String credential)
            throws ServletException, IOException {
        Optional<PlatformPrincipal> principal;
        try {
            principal = identityService.authenticateAccessToken(credential);
        } catch (GitCodeUnavailableException unavailable) {
            // A browser always carries this cookie, so an outage must not break public reads.
            if (requiresIdentity(request)) {
                log.warn("GitCode identity lookup is unavailable; protected route answered 503");
                unavailable(response);
                return;
            }
            log.warn("GitCode identity lookup is unavailable; continuing as anonymous");
            filterChain.doFilter(request, response);
            return;
        }
        // A rejected or expired cookie means "not signed in", not "bad request".
        principal.ifPresent(this::applyAuthentication);
        filterChain.doFilter(request, response);
    }

    private void applyAuthentication(PlatformPrincipal principal) {
        List<SimpleGrantedAuthority> authorities = principal.platformRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private boolean requiresIdentity(HttpServletRequest request) {
        String path = RouteSecurityPolicyRegistry.requestPath(request);
        return routeSecurityPolicyRegistry.accessLevel(request.getMethod(), path)
                != RouteSecurityPolicyRegistry.AccessLevel.PERMIT_ALL;
    }

    private String bearerCredential(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null
                || !authorization.regionMatches(true, 0, "Bearer", 0, "Bearer".length())) {
            return null;
        }
        if (authorization.length() > "Bearer".length()
                && !Character.isWhitespace(authorization.charAt("Bearer".length()))) {
            return null;
        }
        return authorization.length() <= BEARER.length() - 1
                ? ""
                : authorization.substring(Math.min(BEARER.length(), authorization.length())).trim();
    }

    private String cookieCredential(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (ACCESS_TOKEN_COOKIE.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        SecurityContextHolder.clearContext();
        authenticationEntryPoint.commence(request, response,
                new BadCredentialsException("Invalid GitCode credential"));
    }

    private void unavailable(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader(HttpHeaders.RETRY_AFTER, "30");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":503,\"msg\":\"error.auth.gitcode.unavailable\"}");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = RouteSecurityPolicyRegistry.requestPath(request);
        return !path.startsWith("/api/");
    }
}
