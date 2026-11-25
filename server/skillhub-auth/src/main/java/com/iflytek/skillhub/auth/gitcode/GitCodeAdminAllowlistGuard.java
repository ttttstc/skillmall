package com.iflytek.skillhub.auth.gitcode;

import com.iflytek.skillhub.auth.entity.IdentityBinding;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.auth.repository.IdentityBindingRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces the GitCode administration allowlist.
 *
 * <p>Reaching an administration route requires a guaranteed GitCode identity and membership in
 * {@code skillhub.auth.gitcode.admin-allowlist}. The allowlist is consulted per request, so
 * removing an account revokes access immediately. Route-level role checks still apply on top of
 * this guard.
 */
public class GitCodeAdminAllowlistGuard extends OncePerRequestFilter {

    private static final String ADMIN_PATH_PREFIX = "/api/v1/admin/";

    private static final String PROMETHEUS_PATH = "/actuator/prometheus";

    private final GitCodeAuthProperties properties;
    private final IdentityBindingRepository bindingRepository;

    public GitCodeAdminAllowlistGuard(GitCodeAuthProperties properties,
                                      IdentityBindingRepository bindingRepository) {
        this.properties = properties;
        this.bindingRepository = bindingRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof PlatformPrincipal principal)) {
            // Anonymous callers are rejected by the authorization chain, which produces 401.
            filterChain.doFilter(request, response);
            return;
        }
        if (!isAllowlisted(principal)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":403,\"msg\":\"error.auth.gitcode.adminNotAllowlisted\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isAllowlisted(PlatformPrincipal principal) {
        if (!properties.hasAdminAllowlist()) {
            // Fail closed: an administration surface without a configured allowlist is unusable.
            return false;
        }
        List<IdentityBinding> bindings = bindingRepository.findByUserId(principal.userId());
        for (IdentityBinding binding : bindings) {
            if (!GitCodeIdentityService.PROVIDER_CODE.equals(binding.getProviderCode())) {
                continue;
            }
            if (properties.isAdminAllowlisted(binding.getLoginName(), binding.getSubject())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            path = request.getRequestURI();
        }
        return !path.startsWith(ADMIN_PATH_PREFIX) && !PROMETHEUS_PATH.equals(path);
    }
}
