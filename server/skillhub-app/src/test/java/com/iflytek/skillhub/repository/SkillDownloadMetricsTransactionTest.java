package com.iflytek.skillhub.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillDownloadDailyRepository;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionStats;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.service.SkillDownloadMetricsService;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import com.iflytek.skillhub.infra.jpa.NamespaceJpaRepository;
import com.iflytek.skillhub.infra.jpa.SkillJpaRepository;
import com.iflytek.skillhub.infra.jpa.SkillVersionJpaRepository;
import com.iflytek.skillhub.infra.jpa.SkillVersionStatsJpaRepository;
import com.iflytek.skillhub.infra.jpa.UserAccountJpaRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The three download counters of one published download must commit together or not at all.
 *
 * <p>The per-day bucket feeds the 7-day home ranking while {@code skill.download_count} is the
 * cumulative total. If the bucket write could fail after the cumulative total had already
 * committed, every such failure would leave the window permanently short of the total, and no
 * later download could repair the gap. This test fails if the writes are ever split across
 * transactions again.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Import(SkillDownloadMetricsService.class)
// Real commits: the point is which rows survive a failed transaction, so the test itself must not
// wrap everything in a transaction that is rolled back at the end anyway.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SkillDownloadMetricsTransactionTest {

    private static final LocalDate DOWNLOAD_DATE = LocalDate.of(2026, 11, 7);

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
    private SkillDownloadMetricsService metrics;
    @Autowired
    private SkillJpaRepository skills;
    @Autowired
    private SkillVersionJpaRepository versions;
    @Autowired
    private SkillVersionStatsJpaRepository versionStats;
    @Autowired
    private NamespaceJpaRepository namespaces;
    @Autowired
    private UserAccountJpaRepository users;

    /** Stands in for the day-bucket write so one case can make it fail deterministically. */
    @MockBean
    private SkillDownloadDailyRepository dailyBuckets;

    private Long skillId;
    private Long versionId;

    @BeforeEach
    void fixtures() {
        String owner = "owner";
        // Cast through the domain port: the JPA repository inherits both SkillRepository#findById
        // and CrudRepository#findById, which would otherwise make the call ambiguous.
        if (((UserAccountRepository) users).findById(owner).isEmpty()) {
            users.saveAndFlush(new UserAccount(owner, owner, null, null));
        }
        String namespaceSlug = "metrics-" + UUID.randomUUID().toString().substring(0, 8);
        Namespace namespace = namespaces.saveAndFlush(new Namespace(namespaceSlug, "Metrics", owner));

        Skill skill = new Skill(namespace.getId(), "download-counter", owner, SkillVisibility.PUBLIC);
        skill.setDisplayName("Download counter");
        Skill saved = skills.saveAndFlush(skill);

        SkillVersion version = new SkillVersion(saved.getId(), "1.0.0", owner);
        version.setStatus(SkillVersionStatus.PUBLISHED);
        version.setDownloadReady(true);
        SkillVersion savedVersion = versions.saveAndFlush(version);

        skillId = saved.getId();
        versionId = savedVersion.getId();
    }

    @Test
    void dayBucketFailureLeavesTheCumulativeCounterUnchanged() {
        doThrow(new IllegalStateException("day bucket unavailable"))
                .when(dailyBuckets).incrementDownloadCount(anyLong(), any());

        assertThatThrownBy(() -> metrics.recordPublishedDownload(skillId, versionId, DOWNLOAD_DATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("day bucket unavailable");

        // Both earlier writes ran inside the same transaction as the failing one, so neither may
        // survive it: the cumulative total is still zero and no per-version row was created.
        assertThat(downloadCount()).isZero();
        assertThat(versionStats.findBySkillVersionId(versionId)).isEmpty();
    }

    @Test
    void allThreeCountersAdvanceTogetherWhenNothingFails() {
        metrics.recordPublishedDownload(skillId, versionId, DOWNLOAD_DATE);

        assertThat(downloadCount()).isEqualTo(1L);
        Optional<SkillVersionStats> stats = versionStats.findBySkillVersionId(versionId);
        assertThat(stats).isPresent();
        assertThat(stats.orElseThrow().getDownloadCount()).isEqualTo(1L);
        // The window counter is written from the same call, on the UTC day it was given.
        verify(dailyBuckets).incrementDownloadCount(skillId, DOWNLOAD_DATE);
    }

    /** Reads the committed value, not a value still waiting in a persistence context. */
    private long downloadCount() {
        return ((SkillRepository) skills).findById(skillId).orElseThrow().getDownloadCount();
    }
}
