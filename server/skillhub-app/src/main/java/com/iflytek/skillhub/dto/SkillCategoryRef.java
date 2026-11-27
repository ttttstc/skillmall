package com.iflytek.skillhub.dto;

import com.iflytek.skillhub.domain.category.Category;
import com.iflytek.skillhub.domain.category.CategoryDirectoryService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal category reference carried on skill payloads.
 *
 * <p>Only the three fields a card or a detail header actually renders. The display name is resolved
 * server-side for the request locale, so the frontend never has to map a category id to a
 * translation key — categories are data and are created at runtime.
 */
public record SkillCategoryRef(Long id, String slug, String displayName) {

    public static SkillCategoryRef of(Category category, String locale) {
        if (category == null) {
            return null;
        }
        return new SkillCategoryRef(category.getId(), category.getSlug(), category.displayName(locale));
    }

    /**
     * Batch resolver for a page of skills.
     *
     * <p>Resolves every referenced category in one lookup instead of one query per card; the
     * directory table is tiny, so a single {@code IN} query covers the whole page.
     */
    public static final class Pages {

        private final CategoryDirectoryService directory;
        private final String locale;
        private final Map<Long, Category> loaded = new LinkedHashMap<>();

        public Pages(CategoryDirectoryService directory, String locale) {
            this.directory = directory;
            this.locale = locale;
        }

        /** Pre-loads the ids the page is about to reference, so resolution below is map-only. */
        public Pages preload(List<Long> categoryIds) {
            if (categoryIds == null || categoryIds.isEmpty()) {
                return this;
            }
            List<Long> missing = new ArrayList<>();
            for (Long id : categoryIds) {
                if (id != null && !loaded.containsKey(id)) {
                    missing.add(id);
                }
            }
            if (missing.isEmpty()) {
                return this;
            }
            // Tolerate a collaborator that answers null (a stub in a controller test, or a future
            // implementation returning nothing): a missing category must degrade to "no chip", never
            // to a 500 on the whole listing.
            Map<Long, Category> resolved = directory.findByIds(missing);
            if (resolved != null) {
                for (Map.Entry<Long, Category> entry : resolved.entrySet()) {
                    Category category = entry.getValue();
                    if (category != null && category.getId() != null) {
                        loaded.put(category.getId(), category);
                    }
                }
            }
            return this;
        }

        public SkillCategoryRef resolve(Long categoryId) {
            if (categoryId == null) {
                return null;
            }
            Category category = loaded.get(categoryId);
            if (category == null) {
                var found = directory.findById(categoryId);
                category = found == null ? null : found.orElse(null);
                if (category != null) {
                    loaded.put(categoryId, category);
                }
            }
            return of(category, locale);
        }
    }
}
