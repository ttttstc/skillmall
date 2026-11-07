package com.iflytek.skillhub.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Native-SQL implementation of the home page rankings.
 *
 * <p>The SQL is deliberately explicit about the public-distribution contract so an anonymous
 * caller cannot receive a hidden, private, archived or non-distributable skill. Windowed ranking
 * reads only {@code skill_download_daily}; it never falls back to {@code skill.download_count},
 * because a cumulative total cannot stand in for a 7-day figure.
 */
@Repository
public class JpaSkillRankingQueryRepository implements SkillRankingQueryRepository {

    private static final String ELIGIBLE_SKILL_FROM = """
            FROM skill s
            JOIN namespace n ON n.id = s.namespace_id
            JOIN skill_version v ON v.id = s.latest_version_id
            """;

    private static final String ELIGIBLE_SKILL_WHERE = """
            WHERE s.status = 'ACTIVE'
              AND s.hidden = FALSE
              AND n.status <> 'ARCHIVED'
              AND v.status = 'PUBLISHED'
              AND v.download_ready = TRUE
              AND v.yanked_at IS NULL
              AND (s.visibility = 'PUBLIC'
                   OR (s.visibility = 'NAMESPACE_ONLY' AND s.namespace_id IN (:memberNamespaceIds)))
            """;

    /** Deterministic tie-break shared by every ranking so identical counters stay stable. */
    private static final String STABLE_TIE_BREAK = "s.first_published_at DESC NULLS LAST, s.id DESC";

    private static final String WINDOW_SUM = """
            (SELECT COALESCE(SUM(d.download_count), 0)
             FROM skill_download_daily d
             WHERE d.skill_id = s.id
               AND d.download_date >= :windowStart
               AND d.download_date < :windowEndExclusive)
            """;

    private final EntityManager entityManager;

    public JpaSkillRankingQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RankedSkill> findRecentlyPopular(Set<Long> memberNamespaceIds,
                                                 LocalDate windowStart,
                                                 LocalDate windowEndExclusive,
                                                 int limit) {
        String sql = "SELECT s.id, " + WINDOW_SUM + " AS window_count, s.first_published_at "
                + ELIGIBLE_SKILL_FROM + ELIGIBLE_SKILL_WHERE
                + " AND EXISTS (SELECT 1 FROM skill_download_daily d"
                + " WHERE d.skill_id = s.id"
                + " AND d.download_date >= :windowStart"
                + " AND d.download_date < :windowEndExclusive)"
                + " ORDER BY window_count DESC, " + STABLE_TIE_BREAK
                + " LIMIT :limit";
        Query query = entityManager.createNativeQuery(sql);
        bindScope(query, memberNamespaceIds);
        query.setParameter("windowStart", windowStart);
        query.setParameter("windowEndExclusive", windowEndExclusive);
        query.setParameter("limit", limit);
        return rankedSkills(query, true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RankedSkill> findMostDownloaded(Set<Long> memberNamespaceIds, int limit) {
        String sql = "SELECT s.id, NULL, s.first_published_at "
                + ELIGIBLE_SKILL_FROM + ELIGIBLE_SKILL_WHERE
                + " ORDER BY s.download_count DESC, " + STABLE_TIE_BREAK
                + " LIMIT :limit";
        Query query = entityManager.createNativeQuery(sql);
        bindScope(query, memberNamespaceIds);
        query.setParameter("limit", limit);
        return rankedSkills(query, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RankedSkill> findNewestPublished(Set<Long> memberNamespaceIds, int limit) {
        String sql = "SELECT s.id, NULL, s.first_published_at "
                + ELIGIBLE_SKILL_FROM + ELIGIBLE_SKILL_WHERE
                + " ORDER BY s.first_published_at DESC NULLS LAST, s.id DESC"
                + " LIMIT :limit";
        Query query = entityManager.createNativeQuery(sql);
        bindScope(query, memberNamespaceIds);
        query.setParameter("limit", limit);
        return rankedSkills(query, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RankedSkill> findRandomRecommendations(Set<Long> memberNamespaceIds,
                                                       List<Long> excludedSkillIds,
                                                       int limit) {
        boolean hasExclusions = excludedSkillIds != null && !excludedSkillIds.isEmpty();
        String sql = "SELECT s.id, NULL, s.first_published_at "
                + ELIGIBLE_SKILL_FROM + ELIGIBLE_SKILL_WHERE
                + (hasExclusions ? " AND s.id NOT IN (:excludedSkillIds)" : "")
                + " ORDER BY random() LIMIT :limit";
        Query query = entityManager.createNativeQuery(sql);
        bindScope(query, memberNamespaceIds);
        query.setParameter("limit", limit);
        if (hasExclusions) {
            query.setParameter("excludedSkillIds", excludedSkillIds);
        }
        return rankedSkills(query, false);
    }

    private void bindScope(Query query, Set<Long> memberNamespaceIds) {
        // -1 can never match a real id, so an empty member set simply disables the NAMESPACE_ONLY arm.
        query.setParameter("memberNamespaceIds",
                memberNamespaceIds == null || memberNamespaceIds.isEmpty()
                        ? Set.of(-1L)
                        : memberNamespaceIds);
    }

    private List<RankedSkill> rankedSkills(Query query, boolean withWindowCount) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(row -> new RankedSkill(
                        ((Number) row[0]).longValue(),
                        withWindowCount && row[1] != null ? ((Number) row[1]).longValue() : null,
                        instant(row[2])))
                .toList();
    }

    private Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("Expected a first-publication timestamp, got " + value);
    }
}
