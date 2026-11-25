package com.iflytek.skillhub.controller.portal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iflytek.skillhub.auth.entity.IdentityBinding;
import com.iflytek.skillhub.auth.gitcode.GitCodeIdentityService;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.auth.repository.IdentityBindingRepository;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import com.iflytek.skillhub.domain.user.UserStatus;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises the production security chain for the GitCode personal-access-token channel.
 *
 * <p>The deployment under test disables the legacy login surfaces, so a bearer credential can only
 * be a GitCode token, and the administration surface additionally requires the GitCode allowlist.
 */
@SpringBootTest(properties = {
        "skillhub.ratelimit.enabled=false",
        "skillhub.auth.legacy-login.enabled=false",
        "skillhub.auth.gitcode.admin-allowlist=alice:SUPER_ADMIN"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GitCodePatSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GitCodeIdentityService identityService;

    @MockBean
    private IdentityBindingRepository identityBindingRepository;

    @Autowired
    private UserAccountRepository userAccounts;

    @Autowired
    private com.iflytek.skillhub.auth.config.LegacyLoginProperties legacyLoginProperties;

    @Test
    void deploymentModeDisablesTheLegacyLoginSurfaces() {
        org.junit.jupiter.api.Assertions.assertFalse(legacyLoginProperties.isEnabled(),
                "skillhub.auth.legacy-login.enabled must be bound to false in this test");
    }

    @BeforeEach
    void seedAccounts() {
        // The request-context filter rejects principals whose platform account is missing, so the
        // authenticated GitCode identity needs a real account row.
        userAccounts.save(activeAccount("usr-gitcode", "gitcode-user"));
        userAccounts.save(activeAccount("usr-mallory", "mallory"));
    }

    private UserAccount activeAccount(String id, String displayName) {
        UserAccount account = new UserAccount(id, displayName, null, null);
        account.setStatus(UserStatus.ACTIVE);
        return account;
    }

    @Test
    void anonymousAdministrationRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidPersonalAccessTokenIsRejected() throws Exception {
        when(identityService.authenticateAccessToken("bad")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/auth/providers").header("Authorization", "Bearer bad"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void administrationRequestFromANonAllowlistedAccountIsForbidden() throws Exception {
        when(identityService.authenticateAccessToken(any()))
                .thenReturn(Optional.of(principal("usr-mallory", "mallory", Set.of("SUPER_ADMIN"))));
        when(identityBindingRepository.findByUserId("usr-mallory"))
                .thenReturn(List.of(new IdentityBinding("usr-mallory", "gitcode",
                        "aaaaaaaaaaaaaaaaaaaaaaaa", "mallory")));

        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer gitcode-pat"))
                .andExpect(status().isForbidden());
    }

    @Test
    void legacyTokenSurfaceIsNotServedToAnonymousCallers() throws Exception {
        // The token controller is absent in this deployment; anonymous callers are rejected before
        // any route resolution happens.
        mockMvc.perform(get("/api/v1/tokens"))
                .andExpect(status().isUnauthorized());
    }

    private PlatformPrincipal principal(String userId, String displayName, Set<String> roles) {
        return new PlatformPrincipal(userId, displayName, null, null, "gitcode", roles);
    }
}
