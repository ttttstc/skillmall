package com.iflytek.skillhub.repository;

import java.util.List;

/**
 * Read-side projection for the category directory and the creator filter.
 *
 * <p>Both queries have to agree with the public-distribution predicate the list itself uses,
 * otherwise the directory advertises counts the list cannot deliver ("this category has 12" then
 * an empty grid). Keeping them in the app query layer is the established place for joins and
 * presentation projections.
 */
public interface CategoryDirectoryQueryRepository {

    /** One row per (category, sub-category) bucket that currently holds publicly distributable skills. */
    record CategoryCount(Long categoryId, Long subCategoryId, long total) {
    }

    /** A creator who owns at least one publicly distributable skill. */
    record AuthorRow(String userId, String displayName, String avatarUrl, long skillCount) {
    }

    /**
     * Counts across both levels in a single pass. {@code subCategoryId} is null for skills that only
     * carry a first-level category.
     */
    List<CategoryCount> countPublicSkills();

    /**
     * Creators matching {@code query} (case-insensitive substring on the display name), limited to
     * people who actually have publicly distributable skills — the endpoint must not become a
     * general user directory.
     */
    List<AuthorRow> findPublicAuthors(String query, int limit);
}
