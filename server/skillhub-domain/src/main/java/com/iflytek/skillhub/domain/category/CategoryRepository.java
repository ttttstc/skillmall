package com.iflytek.skillhub.domain.category;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Persistence port for the marketplace taxonomy. Implemented in the infra module. */
public interface CategoryRepository {

    Optional<Category> findById(Long id);

    List<Category> findAllByOrderBySortOrderAscIdAsc();

    List<Category> findAllByEnabledTrueOrderBySortOrderAscIdAsc();

    List<Category> findByIdIn(Collection<Long> ids);

    /** Level-1 lookup: the slug namespace of primary categories has no parent. */
    Optional<Category> findBySlugAndParentIdIsNull(String slug);

    /** Level-2 lookup, always scoped by parent so two categories may reuse a child slug. */
    Optional<Category> findBySlugAndParentId(String slug, Long parentId);

    List<Category> findByParentIdOrderBySortOrderAscIdAsc(Long parentId);

    Category save(Category category);
}
