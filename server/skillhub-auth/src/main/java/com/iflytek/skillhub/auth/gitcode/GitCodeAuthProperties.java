package com.iflytek.skillhub.auth.gitcode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the GitCode identity provider.
 *
 * <p>An access token is accepted from {@code Authorization: Bearer <token>} or from the fixed
 * {@code GITCODE_ACCESS_TOKEN} cookie; both are verified against the same GitCode user endpoint.
 */
@Configuration
@ConfigurationProperties(prefix = "skillhub.auth.gitcode")
public class GitCodeAuthProperties {

    /** GitCode v5 API base used to resolve the authenticated user. */
    private String apiBaseUrl = "https://gitcode.net/api/v5";

    /** Explicit user endpoint; empty means {@code {apiBaseUrl}/user}. */
    private String userEndpoint = "";

    /** Connect and read timeout for GitCode identity lookups. */
    private Duration timeout = Duration.ofSeconds(3);

    private final Cache cache = new Cache();

    /**
     * GitCode accounts allowed to reach the administration surface. Entries are
     * {@code login} or {@code login:ROLE} (a comma-separated role list is also accepted), and a
     * bare GitCode account id is accepted instead of a login.
     */
    private List<String> adminAllowlist = new ArrayList<>();

    /** Absolute endpoint used to resolve the current user from an access token. */
    public String resolvedUserEndpoint() {
        if (userEndpoint != null && !userEndpoint.isBlank()) {
            return userEndpoint.trim();
        }
        String base = apiBaseUrl == null ? "" : apiBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/user";
    }

    /**
     * Maps every allowlisted account to the platform roles its entry grants.
     *
     * <p>The key is the lowercase login, or {@code id:<subject>} for entries that address a
     * GitCode account id. The returned set may be empty for entries without roles, which only
     * grants administration access when the database already holds the required roles.
     */
    public Map<String, Set<String>> adminRoleGrants() {
        Map<String, Set<String>> grants = new LinkedHashMap<>();
        for (String rawEntry : adminAllowlist) {
            if (rawEntry == null || rawEntry.isBlank()) {
                continue;
            }
            String entry = rawEntry.trim();
            String account = entry;
            Set<String> roles = new LinkedHashSet<>();
            int separator = entry.indexOf(':');
            if (separator >= 0) {
                account = entry.substring(0, separator).trim();
                for (String role : entry.substring(separator + 1).split(",")) {
                    String normalized = role.trim().toUpperCase(Locale.ROOT);
                    if (!normalized.isEmpty()) {
                        roles.add(normalized);
                    }
                }
            }
            if (!account.isEmpty()) {
                grants.put(accountKey(account), roles);
            }
        }
        return grants;
    }

    /** Whether the given GitCode account may reach the administration surface. */
    public boolean isAdminAllowlisted(String login, String subject) {
        Map<String, Set<String>> grants = adminRoleGrants();
        if (login != null && grants.containsKey(accountKey(login))) {
            return true;
        }
        return subject != null && grants.containsKey(accountKey(subject));
    }

    /** Platform roles granted by the allowlist entry of this account, if any. */
    public Set<String> allowlistedAdminRoles(String login, String subject) {
        Map<String, Set<String>> grants = adminRoleGrants();
        Set<String> roles = new LinkedHashSet<>();
        if (login != null) {
            roles.addAll(grants.getOrDefault(accountKey(login), Set.of()));
        }
        if (subject != null) {
            roles.addAll(grants.getOrDefault(accountKey(subject), Set.of()));
        }
        return roles;
    }

    /** Whether administration access is configured at all. */
    public boolean hasAdminAllowlist() {
        return !adminRoleGrants().isEmpty();
    }

    private static String accountKey(String account) {
        String trimmed = account.trim();
        // A GitCode id is a 24-character hexadecimal string; keep it distinguishable from a login.
        if (trimmed.matches("^[0-9a-fA-F]{24}$")) {
            return "id:" + trimmed.toLowerCase(Locale.ROOT);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getUserEndpoint() {
        return userEndpoint;
    }

    public void setUserEndpoint(String userEndpoint) {
        this.userEndpoint = userEndpoint;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Cache getCache() {
        return cache;
    }


    public List<String> getAdminAllowlist() {
        return adminAllowlist;
    }

    public void setAdminAllowlist(List<String> adminAllowlist) {
        this.adminAllowlist = adminAllowlist == null ? new ArrayList<>() : adminAllowlist;
    }

    /** Positive and negative caching for credential to identity resolution. */
    public static class Cache {

        private Duration positiveTtl = Duration.ofSeconds(300);

        private Duration negativeTtl = Duration.ofSeconds(30);

        public Duration getPositiveTtl() {
            return positiveTtl;
        }

        public void setPositiveTtl(Duration positiveTtl) {
            this.positiveTtl = positiveTtl;
        }

        public Duration getNegativeTtl() {
            return negativeTtl;
        }

        public void setNegativeTtl(Duration negativeTtl) {
            this.negativeTtl = negativeTtl;
        }
    }

}
