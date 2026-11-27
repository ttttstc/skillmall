package com.iflytek.skillhub.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Native implementations of the directory read models.
 *
 * <p>{@link #ELIGIBLE} is deliberately the same shape as the public-distribution predicate used by
 * search and the home rankings. A skill counts as "publicly distributable" only when the container
 * is active and not hidden, its namespace is not archived, and its latest version is a published,
 * downloadable, non-yanked version. Anything looser would let the directory advertise a number the
 * list cannot deliver.
 */
@Repository
public class JpaCategoryDirectoryQueryRepository implements CategoryDirectoryQueryRepository {

    private static final String ELIGIBLE = """
            s.visibility = 'PUBLIC'
              AND s.status = 'ACTIVE'
              AND s.hidden = FALSE
              AND n.status <> 'ARCHIVED'
              AND v.status = 'PUBLISHED'
              AND v.download_ready = TRUE
              AND v.yanked_at IS NULL
            """;

    private static final String ELIGIBLE_JOINS = """
            FROM skill s
            JOIN namespace n ON n.id = s.namespace_id
            JOIN skill_version v ON v.id = s.latest_version_id
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<CategoryCount> countPublicSkills() {
        String sql = "SELECT s.category_id, s.sub_category_id, COUNT(*) " + ELIGIBLE_JOINS
                + " WHERE " + ELIGIBLE
                + "   AND s.category_id IS NOT NULL "
                + " GROUP BY s.category_id, s.sub_category_id";
        List<Object[]> rows = entityManager.createNativeQuery(sql).getResultList();
        List<CategoryCount> counts = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            counts.add(new CategoryCount(
                    row[0] == null ? null : ((Number) row[0]).longValue(),
                    row[1] == null ? null : ((Number) row[1]).longValue(),
                    ((Number) row[2]).longValue()));
        }
        return counts;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<AuthorRow> findPublicAuthors(String query, int limit) {
        String sql = """
                SELECT COALESCE(s.created_by, s.owner_id) AS creator_id,
                       u.display_name,
                       u.avatar_url,
                       COUNT(*) AS total
                """ + ELIGIBLE_JOINS + """
                JOIN user_account u ON u.id = COALESCE(s.created_by, s.owner_id)
                WHERE """ + ELIGIBLE + """
                  AND (:q IS NULL OR u.display_name ILIKE '%' || :q || '%')
                GROUP BY COALESCE(s.created_by, s.owner_id), u.display_name, u.avatar_url
                ORDER BY total DESC, u.display_name ASC
                LIMIT :limit
                """;
        List<Object[]> rows = entityManager.createNativeQuery(sql)
                .setParameter("q", (query == null || query.isBlank()) ? null : query.trim())
                .setParameter("limit", Math.max(1, Math.min(limit, 50)))
                .getResultList();
        List<AuthorRow> authors = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            authors.add(new AuthorRow(
                    (String) row[0],
                    (String) row[1],
                    (String) row[2],
                    ((Number) row[3]).longValue()));
        }
        return authors;
    }
}
