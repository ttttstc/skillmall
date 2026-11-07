package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.TestRedisConfig;
import com.iflytek.skillhub.auth.device.DeviceAuthService;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMember;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.search.SearchIndexService;
import com.iflytek.skillhub.storage.ObjectStorageService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Closes the loop for the per-version change note: a note accepted at publish time has to be
 * readable again from the public version list and from the single-version detail. Writing the
 * note onto the version row and reading it back are covered by separate tests; this one proves
 * both halves still meet through real persistence and the real HTTP response.
 *
 * <p>Runs on PostgreSQL because publishing locks the skill row with
 * {@code FOR NO KEY UPDATE}, which H2 cannot parse.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SkillPublishChangelogReadBackIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SkillPublishService skillPublishService;

    @Autowired
    private NamespaceRepository namespaceRepository;

    @Autowired
    private NamespaceMemberRepository namespaceMemberRepository;

    @Autowired
    private com.iflytek.skillhub.domain.user.UserAccountRepository userAccounts;

    @MockBean
    private SearchIndexService searchIndexService;

    @MockBean
    private ObjectStorageService objectStorageService;

    @MockBean
    private DeviceAuthService deviceAuthService;

    @Test
    void publishedChangelogIsReadableFromVersionListAndVersionDetail() throws Exception {
        createUser("owner-1");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String namespaceSlug = "changelog-ns-" + suffix;
        Namespace namespace = namespaceRepository.save(
                new Namespace(namespaceSlug, "Changelog " + suffix, "owner-1"));
        namespaceMemberRepository.save(
                new NamespaceMember(namespace.getId(), "owner-1", NamespaceRole.MEMBER));

        SkillPublishService.PublishResult result = skillPublishService.publishFromEntries(
                namespaceSlug,
                skillEntries(),
                "owner-1",
                SkillVisibility.PUBLIC,
                Set.of(),
                false,
                null,
                "  首次发布：补充安装步骤  ");

        String versionNumber = result.version().getVersion();
        String base = "/api/v1/skills/" + namespaceSlug + "/" + result.slug();

        mockMvc.perform(get(base + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].version").value(versionNumber))
                .andExpect(jsonPath("$.data.items[0].changelog").value("首次发布：补充安装步骤"));

        mockMvc.perform(get(base + "/versions/" + versionNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(versionNumber))
                .andExpect(jsonPath("$.data.changelog").value("首次发布：补充安装步骤"));
    }

    @Test
    void versionWithoutChangelogReadsBackAsNull() throws Exception {
        createUser("owner-2");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String namespaceSlug = "no-changelog-ns-" + suffix;
        Namespace namespace = namespaceRepository.save(
                new Namespace(namespaceSlug, "No changelog " + suffix, "owner-2"));
        namespaceMemberRepository.save(
                new NamespaceMember(namespace.getId(), "owner-2", NamespaceRole.MEMBER));

        SkillPublishService.PublishResult result = skillPublishService.publishFromEntries(
                namespaceSlug,
                skillEntries(),
                "owner-2",
                SkillVisibility.PUBLIC,
                Set.of(),
                false,
                null,
                "   ");

        mockMvc.perform(get("/api/v1/skills/" + namespaceSlug + "/" + result.slug() + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].changelog").value(org.hamcrest.Matchers.nullValue()));
    }

    private void createUser(String userId) {
        if (userAccounts.findById(userId).isEmpty()) {
            userAccounts.save(new com.iflytek.skillhub.domain.user.UserAccount(userId, userId, null, null));
        }
    }

    private List<PackageEntry> skillEntries() {        byte[] markdown = ("---\nname: changelog-demo\ndescription: Changelog demo\nversion: 1.0.0\n---\n"
                + "# Changelog demo\nInstructions\n").getBytes(StandardCharsets.UTF_8);
        return List.of(new PackageEntry("SKILL.md", markdown, markdown.length, "text/markdown"));
    }
}
