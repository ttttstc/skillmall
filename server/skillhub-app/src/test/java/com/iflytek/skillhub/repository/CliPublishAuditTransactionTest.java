package com.iflytek.skillhub.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMember;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.service.SkillDownloadService;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.service.SkillQueryService;
import com.iflytek.skillhub.domain.skill.service.SkillStorageDeletionCompensationService;
import com.iflytek.skillhub.domain.skill.validation.NoOpPrePublishValidator;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.domain.skill.validation.PrePublishValidator;
import com.iflytek.skillhub.domain.skill.validation.SkillPackageValidator;
import com.iflytek.skillhub.domain.security.SecurityScanService;
import com.iflytek.skillhub.infra.jpa.NamespaceJpaRepository;
import com.iflytek.skillhub.infra.jpa.NamespaceMemberJpaRepository;
import com.iflytek.skillhub.infra.jpa.SkillJpaRepository;
import com.iflytek.skillhub.infra.jpa.SkillVersionJpaRepository;
import com.iflytek.skillhub.infra.jpa.UserAccountJpaRepository;
import com.iflytek.skillhub.infra.jpa.JpaSkillStorageDeletionCompensationRepositoryAdapter;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.iflytek.skillhub.service.SkillDeleteAppService;
import com.iflytek.skillhub.service.SkillSearchAppService;
import com.iflytek.skillhub.service.cli.CliSkillAppService;
import com.iflytek.skillhub.storage.ObjectStorageService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CLI publish entry point must keep the version and its publish audit in one transaction.
 *
 * <p>A direct publication has no review task left to record who released which version, so the
 * audit row is the only trace. If the audit write could fail after the version had already
 * committed, the trace would be lost permanently.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Import({com.iflytek.skillhub.auth.rbac.RbacService.class,
        com.iflytek.skillhub.domain.skill.SkillOwnerAccessPolicy.class,
        SkillPublishService.class, SkillStorageDeletionCompensationService.class,
        JpaSkillStorageDeletionCompensationRepositoryAdapter.class,
        CliSkillAppService.class,
        CliPublishAuditTransactionTest.Configuration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CliPublishAuditTransactionTest {

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @TestConfiguration static class Configuration {
        @Bean SkillMetadataParser metadata() { return new SkillMetadataParser(); }
        @Bean SkillPackageValidator validator(SkillMetadataParser metadata) { return new SkillPackageValidator(metadata); }
        @Bean PrePublishValidator preValidator() { return new NoOpPrePublishValidator(); }
        @Bean ObjectMapper mapper() { return new ObjectMapper().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES); }
        @Bean Clock clock() { return Clock.systemUTC(); }
    }

    @Autowired CliSkillAppService cli;
    @Autowired SkillJpaRepository skills;
    @Autowired SkillVersionJpaRepository versions;
    @Autowired NamespaceJpaRepository namespaces;
    @Autowired NamespaceMemberJpaRepository members;
    @Autowired UserAccountJpaRepository users;
    @MockBean ObjectStorageService storage;
    @MockBean SecurityScanService scanner;
    @MockBean AuditLogService audit;
    @MockBean RequestIdAccessor requestIds;
    @MockBean SkillSearchAppService search;
    @MockBean SkillQueryService queries;
    @MockBean SkillDownloadService downloads;
    @MockBean SkillDeleteAppService deletes;
    @MockBean com.iflytek.skillhub.domain.skill.service.SkillVisibilityService visibilityChanges;
    @MockBean com.iflytek.skillhub.domain.review.ReviewAvatarParticipant avatarParticipant;

    private final AuditRequestContext context = new AuditRequestContext(null, null);
    private String namespace;
    private Long namespaceId;

    @BeforeEach void fixtures() {
        if (((com.iflytek.skillhub.domain.user.UserAccountRepository) users).findById("owner").isEmpty()) {
            users.saveAndFlush(new com.iflytek.skillhub.domain.user.UserAccount("owner", "owner", null, null));
        }
        namespace = "cli-audit-" + UUID.randomUUID().toString().substring(0, 8);
        Namespace created = namespaces.saveAndFlush(new Namespace(namespace, "CLI audit testing", "owner"));
        namespaceId = created.getId();
        members.saveAndFlush(new NamespaceMember(created.getId(), "owner", NamespaceRole.OWNER));
        Mockito.when(scanner.isEnabled()).thenReturn(false);
    }

    private List<PackageEntry> entries(String version) {
        byte[] markdown = ("---\nname: cli-audit-skill\ndescription: CLI audit skill\nversion: " + version
                + "\n---\n# Skill\nInstructions\n").getBytes(StandardCharsets.UTF_8);
        return List.of(new PackageEntry("SKILL.md", markdown, markdown.length, "text/markdown"));
    }

    @Test
    void auditFailureRollsBackThePublication() {
        Mockito.when(audit.record(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("audit store unavailable"));

        assertThatThrownBy(() -> cli.publish(namespace, entries("1.0.0"), "owner", SkillVisibility.PUBLIC,
                Set.of(), context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit store unavailable");

        // The version must not survive the failed audit: the direct publish never leaves a
        // published version without its trace. Only this test's own namespace is inspected,
        // because sibling tests commit real rows into the same database.
        assertThat(skills.findAll().stream()
                .filter(skill -> namespaceId.equals(skill.getNamespaceId()))
                .map(Skill::getSlug)
                .toList()).isEmpty();
    }

    @Test
    void successfulPublishWritesVersionAndAuditTogether() {
        var response = cli.publish(namespace, entries("1.0.0"), "owner", SkillVisibility.PUBLIC, Set.of(), context);

        assertThat(response.version()).isEqualTo("1.0.0");
        Skill persisted = skills.findAll().stream()
                .filter(skill -> namespaceId.equals(skill.getNamespaceId()))
                .filter(skill -> "cli-audit-skill".equals(skill.getSlug()))
                .findFirst().orElseThrow();
        assertThat(persisted.getVisibility()).isEqualTo(SkillVisibility.PUBLIC);
        assertThat(versions.findBySkillIdAndVersion(persisted.getId(), "1.0.0")).isPresent();

        Mockito.verify(audit).record(
                ArgumentMatchers.eq("owner"),
                ArgumentMatchers.eq("SKILL_PUBLISH"),
                ArgumentMatchers.eq("SKILL_VERSION"),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.contains("\"status\":\"PUBLISHED\""));
    }
}
