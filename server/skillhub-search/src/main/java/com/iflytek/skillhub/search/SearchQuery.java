package com.iflytek.skillhub.search;

import com.iflytek.skillhub.domain.skill.SkillDomain;
import java.util.List;

/**
 * Immutable search request model shared between application code and search implementations.
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
         * Optional first-level category filter. {@code null} means "every category"; it is never
         * translated into "no category", because an absent filter must widen the result, not empty it.
         */
        SkillDomain domain
) {
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
                requireInstallableLatest, null);
    }

    public SearchQuery(
            String keyword,
            Long namespaceId,
            SearchVisibilityScope visibilityScope,
            String sortBy,
            int page,
            int size,
            List<String> labelSlugs) {
        this(keyword, namespaceId, visibilityScope, sortBy, page, size, labelSlugs, false, null);
    }

    public SearchQuery(
            String keyword,
            Long namespaceId,
            SearchVisibilityScope visibilityScope,
            String sortBy,
            int page,
            int size) {
        this(keyword, namespaceId, visibilityScope, sortBy, page, size, List.of(), false, null);
    }
}
