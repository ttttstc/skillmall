package com.iflytek.skillhub.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.repository.JpaSkillRankingQueryRepository;
import com.iflytek.skillhub.repository.SkillRankingQueryRepository.RankedSkill;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-SQL coverage for the home rankings, against the migration-managed schema.
 *
 * <p>Flyway is enabled and Hibernate only validates, so this test also proves V120 applies and
 * matches the entity model. Each case states the exact window so the assertions do not depend on
 * the machine clock.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaSkillRankingQueryRepository.class)
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=OFF"
})
class HomeRankingQueryRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);
    private static final LocalDate WINDOW_START = TODAY.minusDays(6);
    private static final LocalDate WINDOW_END_EXCLUSIVE = TODAY.plusDays(1);
    private static final Instant DEFAULT_PUBLISHED_AT = Instant.parse("2026-04-01T08:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JpaSkillRankingQueryRepository repository;

    private final Map<String, Namespace> namespaces = new HashMap<>();

    @BeforeEach
    void setUp() {
        entityManager.persist(new UserAccount("owner", "Owner", null, null));
    }

    @Test
    void recentRankingSumsOnlyBucketsInsideTheSevenDayWindow() {
        // "old-hits" is far ahead cumulatively but barely downloads inside the window.
        Skill oldHits = publicSkill("team", "old-hits");
        bucket(oldHits, TODAY.minusDays(30), 500);
        bucket(oldHits, TODAY, 1);
        nativeUpdate("UPDATE skill SET download_count = 501 WHERE id = " + oldHits.getId());

        Skill newHits = publicSkill("team", "new-hits");
        bucket(newHits, TODAY.minusDays(2), 9);
        bucket(newHits, TODAY, 1);
        nativeUpdate("UPDATE skill SET download_count = 10 WHERE id = " + newHits.getId());

        List<RankedSkill> recent =
                repository.findRecentlyPopular(Set.of(), WINDOW_START, WINDOW_END_EXCLUSIVE, 10);
        assertThat(ids(recent)).containsExactly(newHits.getId(), oldHits.getId());
        // The windowed number is the 10 in-window downloads, not the 501 cumulative ones.
        assertThat(recent).extracting(RankedSkill::windowDownloadCount).containsExactly(10L, 1L);

        // The cumulative ranking is a different question and must order differently.
        assertThat(ids(repository.findMostDownloaded(Set.of(), 10)))
                .containsExactly(oldHits.getId(), newHits.getId());
    }

    @Test
    void recentRankingIgnoresBucketsBeforeTheWindowStart() {
        Skill outside = publicSkill("team", "outside");
        bucket(outside, WINDOW_START.minusDays(1), 50);

        Skill onBoundary = publicSkill("team", "boundary");
        bucket(onBoundary, WINDOW_START, 1);

        assertThat(ids(repository.findRecentlyPopular(Set.of(), WINDOW_START, WINDOW_END_EXCLUSIVE, 10)))
                .containsExactly(onBoundary.getId());
    }

    @Test
    void recentRankingIsEmptyWhenNoBucketExistsRatherThanFallingBackToCumulative() {
        Skill cumulativeOnly = publicSkill("team", "cumulative-only");
        nativeUpdate("UPDATE skill SET download_count = 900 WHERE id = " + cumulativeOnly.getId());

        assertThat(repository.findRecentlyPopular(Set.of(), WINDOW_START, WINDOW_END_EXCLUSIVE, 10))
                .isEmpty();
        assertThat(ids(repository.findMostDownloaded(Set.of(), 10)))
                .containsExactly(cumulativeOnly.getId());
    }

    @Test
    void newestRanksByFirstPublishedTimeNotByTheLatestVersionPublication() {
        // Republished later, but it first went online earlier.
        Skill earlier = bareSkill("team", "earlier");
        setFirstPublishedAt(earlier, Instant.parse("2026-01-05T08:00:00Z"));
        publishVersion(earlier, "2.0.0", Instant.parse("2026-06-01T08:00:00Z"));

        Skill later = bareSkill("team", "later");
        setFirstPublishedAt(later, Instant.parse("2026-03-01T08:00:00Z"));
        publishVersion(later, "1.0.0", Instant.parse("2026-03-01T08:00:00Z"));

        List<RankedSkill> newest = repository.findNewestPublished(Set.of(), 10);
        assertThat(ids(newest)).containsExactly(later.getId(), earlier.getId());
        // The row reports the original first-publication moment, not the 2026-06-01 republish.
        assertThat(newest).extracting(RankedSkill::firstPublishedAt)
                .containsExactly(Instant.parse("2026-03-01T08:00:00Z"), Instant.parse("2026-01-05T08:00:00Z"));
    }

    @Test
    void republishingAVersionKeepsTheOriginalFirstPublishedAt() {
        Skill skill = bareSkill("team", "republished");
        publishVersion(skill, "1.0.0", Instant.parse("2026-01-05T08:00:00Z"));

        SkillVersion second = new SkillVersion(skill.getId(), "1.1.0", "owner");
        second.setStatus(SkillVersionStatus.PUBLISHED);
        second.setDownloadReady(true);
        second.setPublishedAt(Instant.parse("2026-06-01T08:00:00Z"));
        second = entityManager.persistFlushFind(second);
        skill.setLatestVersionId(second.getId());
        skill.markFirstPublished(second.getPublishedAt());
        entityManager.persistAndFlush(skill);
        entityManager.clear();

        Skill reloaded = entityManager.find(Skill.class, skill.getId());
        assertThat(reloaded.getFirstPublishedAt()).isEqualTo(Instant.parse("2026-01-05T08:00:00Z"));
    }

    @Test
    void anonymousResultsExcludeHiddenPrivateArchivedAndUndistributableSkills() {
        Skill visible = publicSkill("team", "visible");
        bucket(visible, TODAY, 1);
        nativeUpdate("UPDATE skill SET download_count = 1 WHERE id = " + visible.getId());

        Skill hidden = publicSkill("team", "hidden");
        nativeUpdate("UPDATE skill SET hidden = TRUE, download_count = 1 WHERE id = " + hidden.getId());

        Skill privateSkill = publicSkill("team", "private-one");
        nativeUpdate("UPDATE skill SET visibility = 'PRIVATE', download_count = 1 WHERE id = " + privateSkill.getId());

        Skill archivedNamespace = publicSkill("old-team", "archived-ns");
        nativeUpdate("UPDATE namespace SET status = 'ARCHIVED' WHERE slug = 'old-team'");
        nativeUpdate("UPDATE skill SET download_count = 1 WHERE id = " + archivedNamespace.getId());

        Skill notReady = publicSkillWithoutDownloadReady("team", "not-ready");
        nativeUpdate("UPDATE skill SET download_count = 1 WHERE id = " + notReady.getId());

        Skill yanked = publicSkill("team", "yanked");
        nativeUpdate("UPDATE skill_version SET yanked_at = CURRENT_TIMESTAMP WHERE skill_id = " + yanked.getId());
        nativeUpdate("UPDATE skill SET download_count = 1 WHERE id = " + yanked.getId());

        Skill archivedContainer = publicSkill("team", "archived-container");
        nativeUpdate("UPDATE skill SET status = 'ARCHIVED', download_count = 1 WHERE id = " + archivedContainer.getId());

        bucket(hidden, TODAY, 5);
        bucket(privateSkill, TODAY, 5);
        bucket(archivedNamespace, TODAY, 5);
        bucket(notReady, TODAY, 5);
        bucket(yanked, TODAY, 5);
        bucket(archivedContainer, TODAY, 5);

        List<Long> recent = ids(repository.findRecentlyPopular(Set.of(), WINDOW_START, WINDOW_END_EXCLUSIVE, 10));
        List<Long> cumulative = ids(repository.findMostDownloaded(Set.of(), 10));
        List<Long> newest = ids(repository.findNewestPublished(Set.of(), 10));
        List<Long> recommended = ids(repository.findRandomRecommendations(Set.of(), List.of(), 10));

        for (List<Long> result : List.of(recent, cumulative, newest, recommended)) {
            assertThat(result).containsExactly(visible.getId());
        }
    }

    @Test
    void namespaceOnlySkillAppearsForMembersAndNotForAnonymousCallers() {
        Namespace team = namespace("team");
        Skill memberOnly = new Skill(team.getId(), "member-only", "owner", SkillVisibility.NAMESPACE_ONLY);
        memberOnly.setDisplayName("Member only");
        memberOnly = entityManager.persistFlushFind(memberOnly);
        publishVersion(memberOnly, "1.0.0", Instant.parse("2026-05-01T08:00:00Z"));

        assertThat(repository.findNewestPublished(Set.of(), 10)).isEmpty();
        assertThat(ids(repository.findNewestPublished(Set.of(team.getId()), 10)))
                .containsExactly(memberOnly.getId());
    }

    @Test
    void recommendationsSkipExcludedIdsAndStayInsideTheEligiblePool() {
        Skill first = publicSkill("team", "first");
        Skill second = publicSkill("team", "second");
        Skill third = publicSkill("team", "third");

        List<Long> picked = ids(repository.findRandomRecommendations(
                Set.of(), List.of(first.getId(), second.getId()), 10));

        // Only the one remaining eligible skill may come back; nothing is invented to fill a row.
        assertThat(picked).containsExactly(third.getId());
    }

    @Test
    void recommendationsReturnOnlyDistinctRealSkills() {
        Skill first = publicSkill("team", "alpha");
        Skill second = publicSkill("team", "beta");
        publicSkillWithoutDownloadReady("team", "not-distributable");

        List<Long> picked = ids(repository.findRandomRecommendations(Set.of(), List.of(), 10));

        assertThat(picked).hasSize(2).containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    @Test
    void skillWithoutALatestVersionAppearsInNoRanking() {
        // The strongest possible counters: only the missing version pointer can keep it out.
        Skill versionless = bareSkill("team", "versionless");
        bucket(versionless, TODAY, 99);
        nativeUpdate("UPDATE skill SET download_count = 99 WHERE id = " + versionless.getId());

        assertThat(repository.findRecentlyPopular(Set.of(), WINDOW_START, WINDOW_END_EXCLUSIVE, 10)).isEmpty();
        assertThat(repository.findMostDownloaded(Set.of(), 10)).isEmpty();
        assertThat(repository.findNewestPublished(Set.of(), 10)).isEmpty();
        assertThat(repository.findRandomRecommendations(Set.of(), List.of(), 10)).isEmpty();
    }

    @Test
    void skillWhoseLatestVersionIsNotPublishedAppearsInNoRanking() {
        // download_ready is TRUE on purpose: the review state alone must exclude it, so the case
        // would fail if the public-distribution filter only checked download readiness.
        Skill pending = bareSkill("team", "pending-review");
        SkillVersion pendingVersion = new SkillVersion(pending.getId(), "1.0.0", "owner");
        pendingVersion.setStatus(SkillVersionStatus.PENDING_REVIEW);
        pendingVersion.setDownloadReady(true);
        pendingVersion = entityManager.persistFlushFind(pendingVersion);
        pending.setLatestVersionId(pendingVersion.getId());
        entityManager.persistAndFlush(pending);

        bucket(pending, TODAY, 99);
        nativeUpdate("UPDATE skill SET download_count = 99 WHERE id = " + pending.getId());

        assertThat(repository.findRecentlyPopular(Set.of(), WINDOW_START, WINDOW_END_EXCLUSIVE, 10)).isEmpty();
        assertThat(repository.findMostDownloaded(Set.of(), 10)).isEmpty();
        assertThat(repository.findNewestPublished(Set.of(), 10)).isEmpty();
        assertThat(repository.findRandomRecommendations(Set.of(), List.of(), 10)).isEmpty();
    }

    @Test
    void newestSortsSkillsWithoutAKnownFirstPublicationAfterEveryDatedSkill() {
        // PostgreSQL sorts NULL first for DESC by default. An unknown first publication must never
        // be presented as the most recent launch, so the ranking has to state NULLS LAST.
        Skill undated = bareSkill("team", "undated");
        publishVersionLeavingFirstPublicationUnset(undated, "1.0.0", DEFAULT_PUBLISHED_AT);

        Skill dated = bareSkill("team", "dated");
        setFirstPublishedAt(dated, Instant.parse("2026-03-01T08:00:00Z"));
        publishVersion(dated, "1.0.0", Instant.parse("2026-03-01T08:00:00Z"));

        List<RankedSkill> newest = repository.findNewestPublished(Set.of(), 10);

        assertThat(ids(newest)).containsExactly(dated.getId(), undated.getId());
        assertThat(newest.get(1).firstPublishedAt()).isNull();
    }

    private static List<Long> ids(List<RankedSkill> rows) {
        return rows.stream().map(RankedSkill::skillId).toList();
    }

    private Namespace namespace(String slug) {
        return namespaces.computeIfAbsent(slug,
                key -> entityManager.persistFlushFind(new Namespace(key, key, "owner")));
    }

    /** Skill with no version yet, so a case can decide the first-publication moment itself. */
    private Skill bareSkill(String namespaceSlug, String slug) {
        Namespace namespace = namespace(namespaceSlug);
        Skill skill = new Skill(namespace.getId(), slug, "owner", SkillVisibility.PUBLIC);
        skill.setDisplayName(slug);
        return entityManager.persistFlushFind(skill);
    }

    /** Distributable public Skill whose latest version is published and download-ready. */
    private Skill publicSkill(String namespaceSlug, String slug) {
        Skill skill = bareSkill(namespaceSlug, slug);
        publishVersion(skill, "1.0.0", DEFAULT_PUBLISHED_AT);
        return skill;
    }

    private Skill publicSkillWithoutDownloadReady(String namespaceSlug, String slug) {
        Skill skill = bareSkill(namespaceSlug, slug);
        publishVersion(skill, "1.0.0", DEFAULT_PUBLISHED_AT, false);
        return skill;
    }

    private void publishVersion(Skill skill, String version, Instant publishedAt) {
        publishVersion(skill, version, publishedAt, true);
    }

    private void publishVersion(Skill skill, String version, Instant publishedAt, boolean downloadReady) {
        SkillVersion skillVersion = new SkillVersion(skill.getId(), version, "owner");
        skillVersion.setStatus(SkillVersionStatus.PUBLISHED);
        skillVersion.setDownloadReady(downloadReady);
        skillVersion.setPublishedAt(publishedAt);
        skillVersion = entityManager.persistFlushFind(skillVersion);
        skill.setLatestVersionId(skillVersion.getId());
        skill.markFirstPublished(publishedAt);
        entityManager.persistAndFlush(skill);
    }

    /** Publishes a version while leaving {@code first_published_at} unset, i.e. "unknown". */
    private void publishVersionLeavingFirstPublicationUnset(Skill skill, String version, Instant publishedAt) {
        SkillVersion skillVersion = new SkillVersion(skill.getId(), version, "owner");
        skillVersion.setStatus(SkillVersionStatus.PUBLISHED);
        skillVersion.setDownloadReady(true);
        skillVersion.setPublishedAt(publishedAt);
        skillVersion = entityManager.persistFlushFind(skillVersion);
        skill.setLatestVersionId(skillVersion.getId());
        entityManager.persistAndFlush(skill);
    }

    private void setFirstPublishedAt(Skill skill, Instant firstPublishedAt) {
        skill.markFirstPublished(firstPublishedAt);
        entityManager.persistAndFlush(skill);
    }

    private void bucket(Skill skill, LocalDate date, long count) {
        entityManager.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO skill_download_daily (skill_id, download_date, download_count)
                        VALUES (?, ?, ?)
                        """)
                .setParameter(1, skill.getId())
                .setParameter(2, date)
                .setParameter(3, count)
                .executeUpdate();
    }

    private void nativeUpdate(String sql) {
        entityManager.getEntityManager().createNativeQuery(sql).executeUpdate();
    }
}
