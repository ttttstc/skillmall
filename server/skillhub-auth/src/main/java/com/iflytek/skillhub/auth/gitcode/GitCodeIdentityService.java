package com.iflytek.skillhub.auth.gitcode;

import com.iflytek.skillhub.auth.entity.IdentityBinding;
import com.iflytek.skillhub.auth.identity.IdentityBindingService;
import com.iflytek.skillhub.auth.oauth.AccountDisabledException;
import com.iflytek.skillhub.auth.oauth.AccountMergedException;
import com.iflytek.skillhub.auth.oauth.AccountPendingException;
import com.iflytek.skillhub.auth.oauth.OAuthClaims;
import com.iflytek.skillhub.auth.oauth.SystemAccountLoginException;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.auth.rbac.PlatformRoleDefaults;
import com.iflytek.skillhub.auth.repository.IdentityBindingRepository;
import com.iflytek.skillhub.auth.repository.UserRoleBindingRepository;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import com.iflytek.skillhub.domain.user.UserStatus;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Turns a GitCode credential into a platform principal.
 *
 * <p>Resolution is cached per credential hash. An existing binding is read without writing, so a
 * steady stream of API calls does not touch the user row; only the first successful resolution
 * creates the platform account, through the same service the OAuth login flow uses.
 */
@Service
public class GitCodeIdentityService {

    private static final Logger log = LoggerFactory.getLogger(GitCodeIdentityService.class);

    /** Stable provider code for GitCode identities, stored in {@code identity_binding}. */
    public static final String PROVIDER_CODE = "gitcode";

    private final GitCodeAuthProperties properties;
    private final GitCodeIdentityClient client;
    private final GitCodeIdentityCache cache;
    private final IdentityBindingService identityBindingService;
    private final IdentityBindingRepository bindingRepository;
    private final UserAccountRepository userRepository;
    private final UserRoleBindingRepository roleBindingRepository;

    public GitCodeIdentityService(GitCodeAuthProperties properties,
                                  GitCodeIdentityClient client,
                                  GitCodeIdentityCache cache,
                                  IdentityBindingService identityBindingService,
                                  IdentityBindingRepository bindingRepository,
                                  UserAccountRepository userRepository,
                                  UserRoleBindingRepository roleBindingRepository) {
        this.properties = properties;
        this.client = client;
        this.cache = cache;
        this.identityBindingService = identityBindingService;
        this.bindingRepository = bindingRepository;
        this.userRepository = userRepository;
        this.roleBindingRepository = roleBindingRepository;
    }

    /**
     * Resolves a GitCode access token to a principal, or empty when GitCode rejects it.
     *
     * <p>The token may come from an explicit bearer header or from the {@code GITCODE_ACCESS_TOKEN}
     * cookie; both are verified the same way. The token's own claims are never trusted as identity:
     * they only skip an obviously expired credential and cross-check the resolved account.
     */
    public Optional<PlatformPrincipal> authenticateAccessToken(String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            return Optional.empty();
        }
        Optional<GitCodeJwtClaims> claims = GitCodeJwtClaims.parse(accessToken);
        if (claims.isPresent() && claims.get().isExpired(Instant.now())) {
            // An expired credential costs no outbound call and simply means "not signed in".
            return Optional.empty();
        }
        String cacheKey = GitCodeIdentityCache.key("access_token", accessToken);
        Optional<GitCodeIdentity> cached = cache.lookup(cacheKey);
        if (cached.isPresent()) {
            if (claims.isPresent() && !claims.get().matches(cached.get())) {
                return Optional.empty();
            }
            return cached.map(this::principalFor);
        }
        if (cache.isNegative(cacheKey)) {
            return Optional.empty();
        }
        Optional<GitCodeIdentity> resolved = client.resolveAccessToken(accessToken);
        if (resolved.isEmpty()) {
            cache.putNegative(cacheKey);
            return Optional.empty();
        }
        GitCodeIdentity identity = resolved.get();
        if (claims.isPresent() && !claims.get().matches(identity)) {
            // The credential and the account it resolved to disagree; fail closed.
            log.warn("GitCode credential claims disagree with the resolved account; rejecting");
            cache.putNegative(cacheKey);
            return Optional.empty();
        }
        cache.putIdentity(cacheKey, identity);
        return Optional.of(principalFor(identity));
    }

    /**
     * Maps a resolved GitCode account onto a platform principal, provisioning the account on first
     * sight.
     */
    public PlatformPrincipal principalFor(GitCodeIdentity identity) {
        if (!identity.hasStableId()) {
            throw new GitCodeUnavailableException("GitCode identity has no stable id");
        }
        Optional<IdentityBinding> binding =
                bindingRepository.findByProviderCodeAndSubject(PROVIDER_CODE, identity.id());
        PlatformPrincipal principal;
        if (binding.isEmpty()) {
            principal = identityBindingService.bindOrCreate(claims(identity), UserStatus.ACTIVE);
        } else {
            UserAccount user = userRepository.findById(binding.get().getUserId())
                    .orElseThrow(() -> new IllegalStateException("User not found for binding"));
            ensureLoginAllowed(user);
            Set<String> roles = roleBindingRepository.findByUserId(user.getId()).stream()
                    .map(roleBinding -> roleBinding.getRole().getCode())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            principal = new PlatformPrincipal(user.getId(), user.getDisplayName(), user.getEmail(),
                    user.getAvatarUrl(), PROVIDER_CODE, PlatformRoleDefaults.withDefaultUserRole(roles));
        }
        return withAllowlistedAdminRoles(principal, identity);
    }

    /**
     * Adds the platform roles configured for this account in the administration allowlist.
     *
     * <p>The allowlist is read on every request, so removing an account takes effect on its next
     * request instead of waiting for a session or cache to expire.
     */
    private PlatformPrincipal withAllowlistedAdminRoles(PlatformPrincipal principal, GitCodeIdentity identity) {
        Set<String> granted = properties.allowlistedAdminRoles(identity.login(), identity.id());
        if (granted.isEmpty() || principal.platformRoles().containsAll(granted)) {
            return principal;
        }
        Set<String> roles = new LinkedHashSet<>(principal.platformRoles());
        roles.addAll(granted);
        return new PlatformPrincipal(principal.userId(), principal.displayName(), principal.email(),
                principal.avatarUrl(), principal.oauthProvider(), roles);
    }

    private OAuthClaims claims(GitCodeIdentity identity) {
        Map<String, Object> extra = new HashMap<>();
        if (StringUtils.hasText(identity.avatarUrl())) {
            extra.put("avatar_url", identity.avatarUrl());
        }
        return new OAuthClaims(PROVIDER_CODE, identity.id(), identity.email(), false,
                identity.login(), extra);
    }

    private void ensureLoginAllowed(UserAccount user) {
        if (user.isSystemAccount()) {
            throw new SystemAccountLoginException();
        }
        if (user.getStatus() == UserStatus.PENDING) {
            throw new AccountPendingException();
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new AccountDisabledException();
        }
        if (user.getStatus() == UserStatus.MERGED) {
            throw new AccountMergedException();
        }
    }
}
