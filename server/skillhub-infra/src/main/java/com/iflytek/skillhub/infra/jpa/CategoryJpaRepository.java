package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.category.Category;
import com.iflytek.skillhub.domain.category.CategoryRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA adapter for {@link CategoryRepository}. Method names follow the Spring Data conventions the
 * rest of the infra module already relies on, so no hand-written query is needed.
 */
@Repository
public interface CategoryJpaRepository extends JpaRepository<Category, Long>, CategoryRepository {

    @Override
    List<Category> findAllByOrderBySortOrderAscIdAsc();

    @Override
    List<Category> findAllByEnabledTrueOrderBySortOrderAscIdAsc();

    @Override
    List<Category> findByIdIn(Collection<Long> ids);

    @Override
    Optional<Category> findBySlugAndParentIdIsNull(String slug);

    @Override
    Optional<Category> findBySlugAndParentId(String slug, Long parentId);

    @Override
    List<Category> findByParentIdOrderBySortOrderAscIdAsc(Long parentId);
}
