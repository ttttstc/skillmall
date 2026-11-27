package com.iflytek.skillhub.domain.category;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the taxonomy and answers the two questions the rest of the system asks of it: "give me the
 * tree to render" and "resolve this slug pair, or reject it loudly".
 *
 * <p>Unknown slugs are rejected instead of silently ignored for the same reason the legacy
 * {@code domain} filter does it: answering a filtered request with the whole catalogue looks like a
 * category that happens to contain everything.
 */
@Service
public class CategoryDirectoryService {

    private final CategoryRepository categories;

    public CategoryDirectoryService(CategoryRepository categories) {
        this.categories = categories;
    }

    /** One level-1 category with its (possibly empty) level-2 children. */
    public record CategoryBranch(Category primary, List<Category> children) {
    }

    /**
     * The enabled tree, ordered by {@code sort_order} then id. Disabled categories disappear from
     * both levels, together with their children — a hidden parent must not leave orphan chips
     * pointing at a branch the user cannot reach.
     */
    @Transactional(readOnly = true)
    public List<CategoryBranch> tree() {
        List<Category> all = categories.findAllByEnabledTrueOrderBySortOrderAscIdAsc();

        Map<Long, List<Category>> childrenByParent = new LinkedHashMap<>();
        List<Category> primaries = new ArrayList<>();
        for (Category category : all) {
            if (category.isPrimary()) {
                primaries.add(category);
            } else if (category.getParentId() != null) {
                childrenByParent.computeIfAbsent(category.getParentId(), key -> new ArrayList<>())
                        .add(category);
            }
        }

        List<CategoryBranch> branches = new ArrayList<>(primaries.size());
        for (Category primary : primaries) {
            branches.add(new CategoryBranch(primary,
                    childrenByParent.getOrDefault(primary.getId(), List.of())));
        }
        return branches;
    }

    /**
     * Resolves a level-1 slug. Unknown or disabled categories are a client error, never "no filter".
     */
    @Transactional(readOnly = true)
    public Category requirePrimary(String slug) {
        return categories.findBySlugAndParentIdIsNull(slug)
                .filter(Category::isEnabled)
                .orElseThrow(() -> new DomainBadRequestException("error.skill.category.unknown", slug));
    }

    /**
     * Resolves a level-2 slug inside one parent. Rejects a child that exists under a different
     * category instead of quietly widening the query to the whole level-1 bucket.
     */
    @Transactional(readOnly = true)
    public Category requireSecondary(Long parentId, String slug) {
        return categories.findBySlugAndParentId(slug, parentId)
                .filter(Category::isEnabled)
                .orElseThrow(() -> new DomainBadRequestException(
                        "error.skill.category.subUnknown", slug));
    }

    /**
     * Empty input means "no filter". Each slug must belong to {@code primary}; mixing children of
     * different primary categories is rejected so the result cannot be an accidental union that
     * the UI never offered.
     */
    @Transactional(readOnly = true)
    public List<Long> resolveSecondaryIds(Category primary, Collection<String> slugs) {
        if (slugs == null || slugs.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(slugs.size());
        for (String slug : slugs) {
            ids.add(requireSecondary(primary.getId(), slug).getId());
        }
        return ids;
    }

    @Transactional(readOnly = true)
    public Optional<Category> findById(Long id) {
        return id == null ? Optional.empty() : categories.findById(id);
    }

    /** Batch lookup used when a page of skills needs their category names. */
    @Transactional(readOnly = true)
    public Map<Long, Category> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Category> byId = new LinkedHashMap<>();
        for (Category category : categories.findByIdIn(ids)) {
            byId.put(category.getId(), category);
        }
        return byId;
    }

    /** Legacy {@code domain} wire value → level-1 category, used by the compatibility filter. */
    @Transactional(readOnly = true)
    public Optional<Category> findByLegacyDomain(String legacyDomain) {
        if (legacyDomain == null || legacyDomain.isBlank()) {
            return Optional.empty();
        }
        String normalized = legacyDomain.trim().toUpperCase().replace('-', '_');
        return categories.findAllByEnabledTrueOrderBySortOrderAscIdAsc().stream()
                .filter(Category::isPrimary)
                .filter(category -> normalized.equals(category.getLegacyDomain()))
                .findFirst();
    }
}
