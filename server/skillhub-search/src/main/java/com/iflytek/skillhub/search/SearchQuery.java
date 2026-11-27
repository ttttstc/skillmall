package com.iflytek.skillhub.search;

import com.iflytek.skillhub.domain.skill.SkillDomain;
import java.util.List;

/**
 * Immutable search request model shared between application code and search implementations.
 *
 * <p>The three taxonomy fields follow one rule: an absent value means "no filter" and must widen
 * the result. `null` must never be translated into "matches nothing", because a filter the caller
 * did not ask for silently emptying the grid is indistinguishable from a broken catalogue.
 */
public record SearchQuery(
        String keyword,
        Long namespaceId,
        SearchVisibilityScope visibilityScope,
        String sortBy,
        int page,
        int size,
        List<String> labelSlugs,
        boolean requireInstallableLatest,
        /**
         * Legacy first-level category filter keyed by the retired {@code SkillDomain} wire value.
         * Kept for API compatibility; {@link #categoryId} wins when both are supplied.
         */
        SkillDomain domain,
        /** First-level category row id. {@code null} means "every category". */
        Long categoryId,
        /** Second-level category row ids; empty means "no second-level filter". */
        List<Long> subCategoryIds,
        /** Creator user id, matched against {@code created_by} (falling back to the owner). */
        String creatorId
) {
    public SearchQuery {
        subCategoryIds = subCategoryIds == null ? List.of() : List.copyOf(subCategoryIds);
    }

    public SearchQuery(
            String keyword,
            Long namespaceId,
            SearchVisibilityScope visibilityScope,
            String sortBy,
            int page,
            int size,
            List<String> labelSlugs,
            boolean requireInstallableLatest,
            SkillDomain domain) {
        this(keyword, namespaceId, visibilityScope, sortBy, page, size, labelSlugs,
                requireInstallableLatest, domain, null, List.of(), null);
    }

    public SearchQuery(
            String keyword,
            Long namespaceId,
            SearchVisibilityScope visibilityScope,
            String sortBy,
            int page,
            int size,
            List<String> labelSlugs,
            boolean requireInstallableLatest) {
        this(keyword, namespaceId, visibilityScope, sortBy, page, size, labelSlugs,
                requireInstallableLatest, null, null, List.of(), null);
    }

    public SearchQuery(
            String keyword,
            Long namespaceId,
            SearchVisibilityScope visibilityScope,
            String sortBy,
            int page,
            int size,
            List<String> labelSlugs) {
        this(keyword, namespaceId, visibilityScope, sortBy, page, size, labelSlugs, false,
                null, null, List.of(), null);
    }

    public SearchQuery(
            String keyword,
            Long namespaceId,
            SearchVisibilityScope visibilityScope,
            String sortBy,
            int page,
            int size) {
        this(keyword, namespaceId, visibilityScope, sortBy, page, size, List.of(), false,
                null, null, List.of(), null);
    }

    public boolean hasTaxonomyFilter() {
        return categoryId != null || !subCategoryIds.isEmpty() || creatorId != null;
    }
}
