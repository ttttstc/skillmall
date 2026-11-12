package com.iflytek.skillhub.gitcode;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.infra.jpa.NamespaceJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real Flyway migrations and lets Hibernate validate the entity against them, so a column
 * rename in {@code V121__gitcode_source_binding.sql} or a wrong derived query fails here instead of
 * at deployment time.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
class GitcodeSourceBindingPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Instant IMPORTED_AT = Instant.parse("2026-11-12T00:00:00Z");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private GitcodeSourceBindingRepository bindings;

    @Autowired
    private NamespaceJpaRepository namespaces;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void persistsAndFindsABindingByItsFullCoordinate() {
        Namespace namespace = namespace("gitcode-team");

        GitcodeSourceBinding binding = binding(namespace.getId(), "skills/foo", "acme", "skills", "main",
                "sha-main", IMPORTED_AT);
        bindings.saveAndFlush(binding);

        assertThat(binding.getId()).isNotNull();
        assertThat(binding.getCreatedAt()).isNotNull();
        assertThat(binding.getUpdatedAt()).isNotNull();

        Optional<GitcodeSourceBinding> found = bindings
                .findByNamespaceIdAndOwnerLoginAndRepositoryNameAndSkillPath(
                        namespace.getId(), "acme", "skills", "skills/foo");
        assertThat(found).isPresent();
        assertThat(found.get().getCommitSha()).isEqualTo("sha-main");
        assertThat(found.get().getRefType()).isEqualTo(GitcodeRefType.BRANCH);
        assertThat(found.get().getLinkType()).isEqualTo(GitcodeLinkType.ONE_SHOT);
        assertThat(found.get().getVisibility()).isEqualTo(SkillVisibility.PUBLIC);
        assertThat(found.get().getSkillVersionId()).isEqualTo(31L);
    }

    @Test
    void distinguishesTheRepositoryRootFromANamedDirectory() {
        Namespace namespace = namespace("gitcode-roots");

        bindings.saveAndFlush(binding(namespace.getId(), "", "acme", "skills", "main", "sha-root", IMPORTED_AT));
        bindings.saveAndFlush(binding(namespace.getId(), "skills/foo", "acme", "skills", "main", "sha-foo",
                IMPORTED_AT));

        assertThat(bindings.findByNamespaceIdAndOwnerLoginAndRepositoryNameAndSkillPath(
                namespace.getId(), "acme", "skills", ""))
                .hasValueSatisfying(root -> assertThat(root.getCommitSha()).isEqualTo("sha-root"));
        assertThat(bindings.findByNamespaceIdAndOwnerLoginAndRepositoryNameAndSkillPath(
                namespace.getId(), "acme", "skills", "skills/foo"))
                .hasValueSatisfying(named -> assertThat(named.getCommitSha()).isEqualTo("sha-foo"));
    }

    @Test
    void listsBindingsNewestFirstWithAStableIdTieBreak() {
        Namespace namespace = namespace("gitcode-listing");

        bindings.saveAndFlush(binding(namespace.getId(), "a", "acme", "skills", "main", "sha-a",
                IMPORTED_AT.minusSeconds(60)));
        bindings.saveAndFlush(binding(namespace.getId(), "b", "acme", "skills", "main", "sha-b", IMPORTED_AT));
        bindings.saveAndFlush(binding(namespace.getId(), "c", "acme", "skills", "main", "sha-c", IMPORTED_AT));

        Page<GitcodeSourceBinding> page = bindings.findByNamespaceIdOrderByLastImportedAtDescIdDesc(
                namespace.getId(), PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(3);
        List<String> paths = page.getContent().stream().map(GitcodeSourceBinding::getSkillPath).toList();
        assertThat(paths).containsExactly("c", "b", "a");
    }

    @Test
    void rejectsASecondBindingForTheSameCoordinate() {
        Namespace namespace = namespace("gitcode-duplicate");

        bindings.saveAndFlush(binding(namespace.getId(), "skills/foo", "acme", "skills", "main", "sha-main",
                IMPORTED_AT));

        assertThatThrownBy(() -> bindings.saveAndFlush(binding(namespace.getId(), "skills/foo", "acme", "skills",
                "dev", "sha-dev", IMPORTED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Namespace namespace(String slug) {
        // namespace.created_by is a foreign key onto user_account, so the fixture owner must exist.
        jdbc.update("INSERT INTO user_account (id, display_name) VALUES ('gitcode-owner', 'GitCode Owner') "
                + "ON CONFLICT (id) DO NOTHING");
        return namespaces.saveAndFlush(new Namespace(slug, slug, "gitcode-owner"));
    }

    private static GitcodeSourceBinding binding(long namespaceId, String skillPath, String owner,
                                                String repository, String ref, String commitSha,
                                                Instant importedAt) {
        GitcodeSourceBinding binding = new GitcodeSourceBinding(namespaceId, owner, repository, skillPath, ref,
                GitcodeRefType.BRANCH, commitSha, GitcodeLinkType.ONE_SHOT, SkillVisibility.PUBLIC,
                "gitcode-owner", importedAt);
        binding.recordImport(ref, GitcodeRefType.BRANCH, commitSha, SkillVisibility.PUBLIC, 41L, 31L, importedAt);
        return binding;
    }
}
