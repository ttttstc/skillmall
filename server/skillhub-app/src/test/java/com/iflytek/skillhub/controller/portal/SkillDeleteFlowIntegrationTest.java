package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.TestRedisConfig;
import com.iflytek.skillhub.auth.device.DeviceAuthService;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.domain.audit.AuditLogRepository;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMember;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillFile;
import com.iflytek.skillhub.domain.skill.SkillFileRepository;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.search.SearchIndexService;
import com.iflytek.skillhub.storage.ObjectStorageService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class SkillDeleteFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NamespaceRepository namespaceRepository;

    @Autowired
    private SkillRepository skillRepository;

    @Autowired
    private SkillVersionRepository skillVersionRepository;

    @Autowired
    private SkillFileRepository skillFileRepository;

    @MockBean
    private SearchIndexService searchIndexService;

    @MockBean
    private ObjectStorageService objectStorageService;

    @Autowired
    private NamespaceMemberRepository namespaceMemberRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @MockBean
    private DeviceAuthService deviceAuthService;

    @Test
    void portalDeleteById_removesPersistedSkillGraphForOwner() throws Exception {
        PersistedSkillGraph graph = createSkillGraph("owner-1");

        mockMvc.perform(delete("/api/web/skills/id/" + graph.skill().getId())
                        .with(authentication(portalAuth("owner-1", "USER")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.skillId").value(graph.skill().getId()))
                .andExpect(jsonPath("$.data.namespace").value(graph.namespace().getSlug()))
                .andExpect(jsonPath("$.data.slug").value(graph.skill().getSlug()))
                .andExpect(jsonPath("$.data.deleted").value(true));

        assertThat(skillRepository.findById(graph.skill().getId())).isEmpty();
        assertThat(skillVersionRepository.findBySkillId(graph.skill().getId())).isEmpty();
        assertThat(skillFileRepository.findByVersionId(graph.version().getId())).isEmpty();
        verify(searchIndexService).remove(graph.skill().getId());
        verify(objectStorageService).deleteObjects(argThat(keys ->
                keys.contains(graph.file().getStorageKey())
                        && keys.contains("packages/" + graph.skill().getId() + "/" + graph.version().getId() + "/bundle.zip")));
    }

    @Test
    void portalDeleteById_rejectsNonOwnerAndKeepsPersistedSkillGraph() throws Exception {
        PersistedSkillGraph graph = createSkillGraph("owner-1");

        mockMvc.perform(delete("/api/web/skills/id/" + graph.skill().getId())
                        .with(authentication(portalAuth("user-2", "USER")))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        assertThat(skillRepository.findById(graph.skill().getId())).isPresent();
        assertThat(skillVersionRepository.findBySkillId(graph.skill().getId())).hasSize(1);
        assertThat(skillFileRepository.findByVersionId(graph.version().getId())).hasSize(1);
        verify(searchIndexService, never()).remove(graph.skill().getId());
        verify(objectStorageService, never()).deleteObjects(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void adminDeleteById_removesPersistedSkillGraphForSuperAdmin() throws Exception {
        PersistedSkillGraph graph = createSkillGraph("owner-1");

        mockMvc.perform(delete("/api/v1/skills/id/" + graph.skill().getId())
                        .with(authentication(apiAuth("super-1", "SUPER_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.skillId").value(graph.skill().getId()))
                .andExpect(jsonPath("$.data.namespace").value(graph.namespace().getSlug()))
                .andExpect(jsonPath("$.data.slug").value(graph.skill().getSlug()))
                .andExpect(jsonPath("$.data.deleted").value(true));

        assertThat(skillRepository.findById(graph.skill().getId())).isEmpty();
        assertThat(skillVersionRepository.findBySkillId(graph.skill().getId())).isEmpty();
        assertThat(skillFileRepository.findByVersionId(graph.version().getId())).isEmpty();
        verify(searchIndexService).remove(graph.skill().getId());
    }

    /**
     * The author removal must also stop the public surface, not just delete the rows:
     * detail, versions, resolve and download must all refuse a visitor afterwards, and the
     * deletion has to stay auditable.
     *
     * <p>A missing Skill currently surfaces as {@code error.skill.notFound} through
     * {@code DomainBadRequestException} (HTTP 400) on these read paths rather than 404, so the
     * assertion is on the 4xx refusal instead of one specific status. That convention predates
     * this change and is intentionally left untouched.
     */
    @Test
    void authorDeleteStopsPublicDetailResolveVersionsAndDownload() throws Exception {
        PersistedSkillGraph graph = createSkillGraph("owner-1");
        String publicBase = "/api/v1/skills/" + graph.namespace().getSlug() + "/" + graph.skill().getSlug();

        mockMvc.perform(delete("/api/web/skills/" + graph.namespace().getSlug() + "/" + graph.skill().getSlug())
                        .with(authentication(portalAuth("owner-1", "USER")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));

        mockMvc.perform(get(publicBase)).andExpect(status().is4xxClientError());
        mockMvc.perform(get(publicBase + "/resolve").param("tag", "latest")).andExpect(status().is4xxClientError());
        mockMvc.perform(get(publicBase + "/versions")).andExpect(status().is4xxClientError());
        mockMvc.perform(get(publicBase + "/download")).andExpect(status().is4xxClientError());
        mockMvc.perform(get(publicBase + "/versions/1.0.0/download")).andExpect(status().is4xxClientError());

        assertThat(auditLogRepository.search("owner-1", "DELETE_SKILL_HARD", PageRequest.of(0, 10)))
                .anySatisfy(log -> {
                    assertThat(log.getAction()).isEqualTo("DELETE_SKILL_HARD");
                    assertThat(log.getTargetType()).isEqualTo("SKILL");
                    assertThat(log.getTargetId()).isEqualTo(graph.skill().getId());
                });
    }

    @Test
    void portalDeleteByCoordinate_rejectsAnotherUsersSkillAndKeepsIt() throws Exception {
        PersistedSkillGraph graph = createSkillGraph("owner-1");

        mockMvc.perform(delete("/api/web/skills/" + graph.namespace().getSlug() + "/" + graph.skill().getSlug())
                        .with(authentication(portalAuth("user-2", "USER")))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(skillRepository.findById(graph.skill().getId())).isPresent();
        assertThat(skillVersionRepository.findBySkillId(graph.skill().getId())).hasSize(1);
        verify(searchIndexService, never()).remove(graph.skill().getId());
    }

    /**
     * The CLI deletes by coordinate, so the API token's {@code skill:delete} scope decides
     * whether the entry point may be called at all — it must not decide whose skill may be
     * deleted. A token holder who does not own the skill gets 403 and the rows stay untouched.
     */
    @Test
    void cliDelete_rejectsNonOwnerAndKeepsPersistedSkillGraph() throws Exception {
        // Positive control: the very same CLI coordinate is deletable by its owner.
        PersistedSkillGraph ownedByCaller = createSkillGraph("owner-1");
        mockMvc.perform(delete("/api/cli/v1/skills/" + ownedByCaller.namespace().getSlug()
                                + "/" + ownedByCaller.skill().getSlug())
                        .with(authentication(apiAuth("owner-1", "USER")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true));
        assertThat(skillRepository.findById(ownedByCaller.skill().getId())).isEmpty();
        // Forget the successful delete's storage call so the negative assertion below is about
        // the rejected request only.
        org.mockito.Mockito.clearInvocations(objectStorageService);

        PersistedSkillGraph graph = createSkillGraph("owner-1");

        mockMvc.perform(delete("/api/cli/v1/skills/" + graph.namespace().getSlug() + "/" + graph.skill().getSlug())
                        .with(authentication(apiAuth("user-2", "USER")))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        // The rejected delete must leave the persisted graph, the search document and the
        // audit trail exactly as they were.
        assertThat(skillRepository.findById(graph.skill().getId())).isPresent();
        assertThat(skillVersionRepository.findBySkillId(graph.skill().getId())).hasSize(1);
        assertThat(skillFileRepository.findByVersionId(graph.version().getId())).hasSize(1);
        verify(searchIndexService, never()).remove(graph.skill().getId());
        verify(objectStorageService, never()).deleteObjects(org.mockito.ArgumentMatchers.anyList());
        assertThat(auditLogRepository.search("user-2", "DELETE_SKILL_HARD", PageRequest.of(0, 10)))
                .isEmpty();
        assertThat(auditLogRepository.search("owner-1", "DELETE_SKILL_HARD", PageRequest.of(0, 10)))
                .extracting(log -> log.getTargetId())
                .doesNotContain(graph.skill().getId());
    }

    @Test
    void cliDelete_removesPersistedSkillGraphForOwnerAndKeepsAuditTrail() throws Exception {
        PersistedSkillGraph graph = createSkillGraph("owner-1");

        mockMvc.perform(delete("/api/cli/v1/skills/" + graph.namespace().getSlug() + "/" + graph.skill().getSlug())
                        .with(authentication(apiAuth("owner-1", "USER")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.namespace").value(graph.namespace().getSlug()))
                .andExpect(jsonPath("$.data.slug").value(graph.skill().getSlug()));

        assertThat(skillRepository.findById(graph.skill().getId())).isEmpty();
        assertThat(skillVersionRepository.findBySkillId(graph.skill().getId())).isEmpty();
        verify(searchIndexService).remove(graph.skill().getId());
        assertThat(auditLogRepository.search("owner-1", "DELETE_SKILL_HARD", PageRequest.of(0, 10)))
                .anySatisfy(log -> {
                    assertThat(log.getAction()).isEqualTo("DELETE_SKILL_HARD");
                    assertThat(log.getTargetType()).isEqualTo("SKILL");
                    assertThat(log.getTargetId()).isEqualTo(graph.skill().getId());
                });
    }

    private PersistedSkillGraph createSkillGraph(String ownerId) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Namespace namespace = namespaceRepository.save(new Namespace("global-delete-" + suffix, "Global Delete " + suffix, "system"));
        namespaceMemberRepository.save(new NamespaceMember(namespace.getId(), ownerId, NamespaceRole.MEMBER));

        Skill skill = new Skill(namespace.getId(), "demo-skill-" + suffix, ownerId, SkillVisibility.PUBLIC);
        skill.setCreatedBy(ownerId);
        skill.setUpdatedBy(ownerId);
        skill = skillRepository.save(skill);
        skillRepository.flush();

        SkillVersion version = new SkillVersion(skill.getId(), "1.0.0", ownerId);
        version = skillVersionRepository.save(version);
        skillVersionRepository.flush();

        skill.setLatestVersionId(version.getId());
        skill.setUpdatedBy(ownerId);
        skill = skillRepository.save(skill);
        skillRepository.flush();

        SkillFile file = skillFileRepository.save(new SkillFile(
                version.getId(),
                "SKILL.md",
                12L,
                "text/markdown",
                "sha-" + suffix,
                "skills/" + skill.getId() + "/" + version.getId() + "/SKILL.md"
        ));

        return new PersistedSkillGraph(namespace, skill, version, file);
    }

    private UsernamePasswordAuthenticationToken portalAuth(String userId, String... roles) {
        PlatformPrincipal principal = new PlatformPrincipal(
                userId,
                userId,
                userId + "@example.com",
                "",
                "session",
                Set.of(roles)
        );
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private UsernamePasswordAuthenticationToken apiAuth(String userId, String... roles) {
        PlatformPrincipal principal = new PlatformPrincipal(
                userId,
                userId,
                userId + "@example.com",
                "",
                "api_token",
                Set.of(roles)
        );
        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        authorities.add(new SimpleGrantedAuthority("SCOPE_skill:delete"));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private record PersistedSkillGraph(Namespace namespace, Skill skill, SkillVersion version, SkillFile file) {
    }
}
