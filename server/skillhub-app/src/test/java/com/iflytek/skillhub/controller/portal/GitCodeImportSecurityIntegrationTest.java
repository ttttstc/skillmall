package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.auth.entity.ApiToken;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.auth.token.ApiTokenService;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.SkillImportSource;
import com.iflytek.skillhub.domain.skill.service.SkillQueryService;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import com.iflytek.skillhub.dto.GitCodeImportPublishRequest;
import com.iflytek.skillhub.dto.GitCodeImportPublishResponse;
import com.iflytek.skillhub.dto.GitCodeImportResolveRequest;
import com.iflytek.skillhub.dto.GitCodeImportResolveResponse;
import com.iflytek.skillhub.service.GitCodeImportAppService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the production security chain for the GitCode import endpoints: anonymous callers must
 * be rejected with 401 before any provider call, and API tokens must hold the expected scope.
 */
@SpringBootTest(properties = "skillhub.ratelimit.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GitCodeImportSecurityIntegrationTest {

    private static final String RESOLVE = "/api/web/skills/import/gitcode/resolve";
    private static final String PUBLISH = "/api/web/skills/import/gitcode/publish";
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String DIGEST = "a".repeat(64);

    @Autowired MockMvc mockMvc;
    @Autowired UserAccountRepository users;
    @MockBean ApiTokenService tokens;
    @MockBean GitCodeImportAppService imports;
    @MockBean SkillQueryService skills;

    private String userId;
    private String rawToken;

    @BeforeEach
    void setUp() {
        userId = "gitcode-security-" + UUID.randomUUID();
        users.save(new UserAccount(userId, "GitCode user", userId + "@example.test", ""));
    }

    @Test
    void anonymousCannotResolve() throws Exception {
        mockMvc.perform(post(RESOLVE).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://gitcode.com/openharmony/docs\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(imports);
    }

    @Test
    void anonymousCannotPublish() throws Exception {
        mockMvc.perform(post(PUBLISH).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://gitcode.com/openharmony/docs\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(imports);
    }

    @Test
    void anonymousCannotReadProvenance() throws Exception {
        mockMvc.perform(get("/api/web/skills/global/demo/versions/1.0.0/import-source").with(csrf()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(imports);
    }

    @Test
    void authenticatedSessionCanResolveAndPublish() throws Exception {
        given(imports.resolve(any(GitCodeImportResolveRequest.class)))
                .willReturn(new GitCodeImportResolveResponse("GITCODE",
                        "https://gitcode.com/openharmony/docs", "openharmony", "docs", "master",
                        COMMIT, List.of(new GitCodeImportResolveResponse.Candidate("skills/alpha")), null));
        given(imports.publish(eq(userId), any(), any(GitCodeImportPublishRequest.class), any()))
                .willReturn(new GitCodeImportPublishResponse(5L, 9L, "ns", "alpha", "1.0.0", "PUBLISHED",
                        1, 5, "https://gitcode.com/openharmony/docs", "skills/alpha", "master",
                        COMMIT, DIGEST));

        mockMvc.perform(post(RESOLVE).session(session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://gitcode.com/openharmony/docs\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commit").value(COMMIT));

        mockMvc.perform(post(PUBLISH).session(session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://gitcode.com/openharmony/docs\","
                                + "\"commit\":\"" + COMMIT + "\","
                                + "\"path\":\"skills/alpha\",\"namespace\":\"ns\",\"visibility\":\"PUBLIC\","
                                + "\"contentDigest\":\"" + DIGEST + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentDigest").value(DIGEST));

        verify(imports).resolve(any(GitCodeImportResolveRequest.class));
        verify(imports).publish(eq(userId), any(), any(GitCodeImportPublishRequest.class), any());
    }

    @Test
    void ownerCanReadProvenanceThroughTheVisibilityCheckedVersionLookup() throws Exception {
        given(skills.getVersionDetail(eq("global"), eq("demo"), eq("1.0.0"), eq(userId), any()))
                .willReturn(new SkillQueryService.SkillVersionDetailDTO(
                        7L, "1.0.0", "PUBLISHED", null, 2, 10L, null, null, null));
        given(imports.source(eq("global"), eq("demo"), eq("1.0.0"), any(), any()))
                .willReturn(Optional.of(SkillImportSource.unpublished(
                        7L, SkillImportSource.PROVIDER_GITCODE, "https://gitcode.com/openharmony/docs",
                        "openharmony", "docs", "main", COMMIT, "skills/alpha", DIGEST, "[]", 1, 5, userId)));

        mockMvc.perform(get("/api/web/skills/global/demo/versions/1.0.0/import-source").session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commit").value(COMMIT))
                .andExpect(jsonPath("$.data.skillPath").value("skills/alpha"))
                .andExpect(jsonPath("$.data.contentDigest").value(DIGEST))
                .andExpect(jsonPath("$.data.importedBy").value(userId));
    }

    @Test
    void unreadableVersionYieldsNotFoundWithoutLeakingProvenance() throws Exception {
        // The visibility-checked version lookup rejects a version the caller may not inspect; the
        // provenance route must then behave like that lookup instead of answering with metadata.
        given(skills.getVersionDetail(eq("global"), eq("demo"), eq("1.0.0"), eq(userId), any()))
                .willThrow(new DomainNotFoundException("error.notFound"));

        mockMvc.perform(get("/api/web/skills/global/demo/versions/1.0.0/import-source").session(session()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("gitcode.com"))))
                .andExpect(content().string(not(containsString(COMMIT))))
                .andExpect(content().string(not(containsString(DIGEST))));
    }

    @Test
    void missingProvenanceForAVisibleVersionIsNotFound() throws Exception {
        given(skills.getVersionDetail(eq("global"), eq("demo"), eq("1.0.0"), eq(userId), any()))
                .willReturn(new SkillQueryService.SkillVersionDetailDTO(
                        7L, "1.0.0", "PUBLISHED", null, 2, 10L, null, null, null));
        given(imports.source(eq("global"), eq("demo"), eq("1.0.0"), any(), any())).willReturn(Optional.empty());

        mockMvc.perform(get("/api/web/skills/global/demo/versions/1.0.0/import-source").session(session()))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {RESOLVE, PUBLISH})
    void publishTokenWithPublishScopeCanImport(String path) throws Exception {
        givenToken("[\"skill:publish\"]");
        given(imports.resolve(any(GitCodeImportResolveRequest.class)))
                .willReturn(new GitCodeImportResolveResponse("GITCODE",
                        "https://gitcode.com/openharmony/docs", "openharmony", "docs", "master",
                        COMMIT, List.of(), null));

        mockMvc.perform(post(path).with(bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://gitcode.com/openharmony/docs\"}"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {RESOLVE, PUBLISH})
    void readOnlyTokenCannotImport(String path) throws Exception {
        givenToken("[\"skill:read\"]");

        mockMvc.perform(post(path).with(bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://gitcode.com/openharmony/docs\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(imports);
    }

    private void givenToken(String scopesJson) {
        rawToken = "gitcode-test-" + UUID.randomUUID();
        given(tokens.validateToken(rawToken)).willReturn(Optional.of(
                new ApiToken(userId, "gitcode", "gitcode", "test-hash", scopesJson)));
    }

    private MockHttpSession session() {
        var context = SecurityContextHolder.createEmptyContext();
        var principal = new PlatformPrincipal(userId, "GitCode user", userId + "@example.test", "", "local", Set.of());
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private RequestPostProcessor bearer() {
        String token = rawToken;
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    private RequestPostProcessor csrf() {
        return request -> {
            String token = UUID.randomUUID().toString();
            request.setCookies(new Cookie("XSRF-TOKEN", token));
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }
}
