package com.iflytek.skillhub.auth.config;

import com.iflytek.skillhub.auth.oauth.CustomOAuth2UserService;
import com.iflytek.skillhub.auth.oauth.CustomOidcUserService;
import com.iflytek.skillhub.auth.oauth.DispatchingTokenResponseClient;
import com.iflytek.skillhub.auth.oauth.OAuth2LoginFailureHandler;
import com.iflytek.skillhub.auth.oauth.OAuth2LoginSuccessHandler;
import com.iflytek.skillhub.auth.oauth.SkillHubOAuth2AuthorizationRequestResolver;
import com.iflytek.skillhub.auth.gitcode.GitCodeAdminAllowlistGuard;
import com.iflytek.skillhub.auth.gitcode.GitCodeAuthProperties;
import com.iflytek.skillhub.auth.gitcode.GitCodeIdentityService;
import com.iflytek.skillhub.auth.gitcode.GitCodeCredentialAuthenticationFilter;
import com.iflytek.skillhub.auth.mock.MockAuthFilter;
import com.iflytek.skillhub.auth.repository.IdentityBindingRepository;
import com.iflytek.skillhub.auth.config.LegacyLoginProperties;
import com.iflytek.skillhub.auth.policy.RouteSecurityPolicyRegistry;
import com.iflytek.skillhub.auth.session.ExpiredPublicSessionFilter;
import com.iflytek.skillhub.auth.session.CorruptSessionRemover;
import com.iflytek.skillhub.auth.token.ApiTokenAuthenticationFilter;
import com.iflytek.skillhub.auth.token.ApiTokenScopeFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Central Spring Security configuration for browser sessions, API tokens, and
 * public versus protected endpoints.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
            "img-src 'self' data: blob: https:",
            "font-src 'self' data: https://fonts.gstatic.com",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "frame-ancestors 'none'",
            "form-action 'self'");

    private final CustomOAuth2UserService customOAuth2UserService;
    private final CustomOidcUserService customOidcUserService;
    private final DispatchingTokenResponseClient tokenResponseClient;
    private final SkillHubOAuth2AuthorizationRequestResolver authorizationRequestResolver;
    private final OAuth2LoginSuccessHandler successHandler;
    private final OAuth2LoginFailureHandler failureHandler;
    private final ApiTokenAuthenticationFilter apiTokenAuthenticationFilter;
    private final ApiTokenScopeFilter apiTokenScopeFilter;
    private final AuthenticationEntryPoint apiAuthenticationEntryPoint;
    private final AccessDeniedHandler apiAccessDeniedHandler;
    private final ObjectProvider<MockAuthFilter> mockAuthFilterProvider;
    private final RouteSecurityPolicyRegistry routeSecurityPolicyRegistry;
    private final CorruptSessionRemover corruptSessionRemover;
    private final String sessionCookieName;
    private final LegacyLoginProperties legacyLoginProperties;
    private final GitCodeIdentityService gitCodeIdentityService;
    private final GitCodeAuthProperties gitCodeAuthProperties;
    private final IdentityBindingRepository identityBindingRepository;

    public SecurityConfig(CustomOAuth2UserService customOAuth2UserService,
                          CustomOidcUserService customOidcUserService,
                          DispatchingTokenResponseClient tokenResponseClient,
                          SkillHubOAuth2AuthorizationRequestResolver authorizationRequestResolver,
                          OAuth2LoginSuccessHandler successHandler,
                          OAuth2LoginFailureHandler failureHandler,
                          ApiTokenAuthenticationFilter apiTokenAuthenticationFilter,
                          ApiTokenScopeFilter apiTokenScopeFilter,
                          AuthenticationEntryPoint apiAuthenticationEntryPoint,
                          AccessDeniedHandler apiAccessDeniedHandler,
                          ObjectProvider<MockAuthFilter> mockAuthFilterProvider,
                          RouteSecurityPolicyRegistry routeSecurityPolicyRegistry,
                          ObjectProvider<CorruptSessionRemover> corruptSessionRemoverProvider,
                          @Value("${server.servlet.session.cookie.name:SESSION}") String sessionCookieName,
                          LegacyLoginProperties legacyLoginProperties,
                          GitCodeIdentityService gitCodeIdentityService,
                          GitCodeAuthProperties gitCodeAuthProperties,
                          IdentityBindingRepository identityBindingRepository) {
        this.customOAuth2UserService = customOAuth2UserService;
        this.customOidcUserService = customOidcUserService;
        this.tokenResponseClient = tokenResponseClient;
        this.authorizationRequestResolver = authorizationRequestResolver;
        this.successHandler = successHandler;
        this.failureHandler = failureHandler;
        this.apiTokenAuthenticationFilter = apiTokenAuthenticationFilter;
        this.apiTokenScopeFilter = apiTokenScopeFilter;
        this.apiAuthenticationEntryPoint = apiAuthenticationEntryPoint;
        this.apiAccessDeniedHandler = apiAccessDeniedHandler;
        this.mockAuthFilterProvider = mockAuthFilterProvider;
        this.routeSecurityPolicyRegistry = routeSecurityPolicyRegistry;
        this.corruptSessionRemover = corruptSessionRemoverProvider.getIfAvailable(() -> sessionId -> {
            throw new IllegalStateException("Corrupt session recovery is not configured");
        });
        this.sessionCookieName = sessionCookieName;
        this.legacyLoginProperties = legacyLoginProperties;
        this.gitCodeIdentityService = gitCodeIdentityService;
        this.gitCodeAuthProperties = gitCodeAuthProperties;
        this.identityBindingRepository = identityBindingRepository;
    }

    /**
     * Builds the ordered security filter chain used by both browser and API
     * clients.
     *
     * <p>The chain mixes session-based authentication, bearer token support,
     * CSRF rules for browser traffic, and method-level authorization.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);
        RequestMatcher csrfIgnoreMatcher = request -> {
            String path = RouteSecurityPolicyRegistry.requestPath(request);
            String authorization = request.getHeader("Authorization");
            boolean cookieAuthHeader = "1".equals(request.getHeader(GitCodeCredentialAuthenticationFilter.COOKIE_AUTH_HEADER));
            return routeSecurityPolicyRegistry.shouldIgnoreCsrf(
                    request.getMethod(), path, authorization, hasSessionCookie(request), cookieAuthHeader);
        };

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfHandler)
                .ignoringRequestMatchers(csrfIgnoreMatcher)
            )
            .authorizeHttpRequests(auth -> {
                configureRoutePolicies(auth);
                auth.anyRequest().authenticated();
            })
            .oauth2Login(oauth2 -> oauth2
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(authorizationRequestResolver))
                .tokenEndpoint(token -> token.accessTokenResponseClient(tokenResponseClient))
                .userInfoEndpoint(userInfo -> userInfo
                    .userService(customOAuth2UserService)
                    .oidcUserService(customOidcUserService))
                .successHandler(successHandler)
                .failureHandler(failureHandler)
            )
            .headers(headers -> headers
                .contentTypeOptions(contentTypeOptions -> {})
                .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                .frameOptions(frameOptions -> frameOptions.deny())
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .invalidSessionStrategy((request, response) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"code\":401,\"msg\":\"Session expired\"}");
                })
            )
            .exceptionHandling(exceptions -> exceptions
                .accessDeniedHandler(apiAccessDeniedHandler)
                .defaultAuthenticationEntryPointFor(
                    apiAuthenticationEntryPoint,
                    new AntPathRequestMatcher("/api/**")
                )
            )
            .logout(logout -> logout
                .logoutUrl("/api/v1/auth/logout")
                // Redirect to the deployment root honoring any sub-path prefix (X-Forwarded-Prefix
                // is reflected into the context path), so logout does not escape a sub-path deployment.
                .logoutSuccessHandler((request, response, authentication) -> {
                    String contextPath = request.getContextPath();
                    response.sendRedirect(((contextPath == null) ? "" : contextPath) + "/");
                })
                .invalidateHttpSession(true)
                .deleteCookies(sessionCookieName)
            )
            .addFilterBefore(new ExpiredPublicSessionFilter(
                    routeSecurityPolicyRegistry, corruptSessionRemover, sessionCookieName), CsrfFilter.class);

        // A GitCode deployment replaces the platform's own credential surfaces with two GitCode
        // channels: an OAuth2 session for browsers and a personal access token for API callers.
        // The legacy bearer-token filters are left out of the chain so a SkillHub token can never
        // authenticate even if one still exists in the database.
        if (legacyLoginProperties.isEnabled()) {
            http.addFilterBefore(apiTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                    .addFilterAfter(apiTokenScopeFilter, ApiTokenAuthenticationFilter.class);
        } else {
            // The filters are constructed here instead of being declared as beans: a Filter bean
            // would also be auto-registered by the servlet container and would then run outside
            // this chain, ignoring the deployment switch.
            http.addFilterBefore(
                    new GitCodeCredentialAuthenticationFilter(gitCodeIdentityService, apiAuthenticationEntryPoint, routeSecurityPolicyRegistry),
                    UsernamePasswordAuthenticationFilter.class);
            // The allowlist only has meaning when the GitCode channels are the sole identity source.
            http.addFilterBefore(
                    new GitCodeAdminAllowlistGuard(gitCodeAuthProperties, identityBindingRepository),
                    AuthorizationFilter.class);
        }

        MockAuthFilter mockAuthFilter = mockAuthFilterProvider.getIfAvailable();
        if (mockAuthFilter != null) {
            if (!legacyLoginProperties.isEnabled()) {
                LOG.warn("Mock authentication is enabled while the legacy login surfaces are disabled; "
                        + "this bypass must never be enabled in a GitCode deployment");
            }
            http.addFilterBefore(mockAuthFilter, AnonymousAuthenticationFilter.class);
        }

        return http.build();
    }

    /**
     * Provides the password encoder shared by local credentials and bootstrap
     * flows.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    private void configureRoutePolicies(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth) {
        for (RouteSecurityPolicyRegistry.RouteAuthorizationPolicy policy : routeSecurityPolicyRegistry.authorizationPolicies()) {
            switch (policy.accessLevel()) {
                case PERMIT_ALL -> auth.requestMatchers(policy.toRequestMatcher()).permitAll();
                case AUTHENTICATED -> auth.requestMatchers(policy.toRequestMatcher()).authenticated();
                case ROLE_PROTECTED -> auth.requestMatchers(policy.toRequestMatcher()).hasAnyRole(policy.roles());
            }
        }
    }

    static boolean hasSessionCookie(HttpServletRequest request) {
        if (request.getRequestedSessionId() != null) {
            return true;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if ("SESSION".equals(cookie.getName()) || "JSESSIONID".equals(cookie.getName())) {
                return true;
            }
        }
        return false;
    }
}
