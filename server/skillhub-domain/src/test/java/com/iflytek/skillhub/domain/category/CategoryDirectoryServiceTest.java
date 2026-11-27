package com.iflytek.skillhub.domain.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tree assembly and slug resolution.
 *
 * <p>Two behaviours are contract, not convenience: an unknown slug is a client error rather than
 * "no filter" (otherwise a typo answers with the whole catalogue and looks like a broken category),
 * and a second-level slug is only resolvable inside its own parent (a child slug is not unique
 * across the tree).
 */
class CategoryDirectoryServiceTest {

    private CategoryRepository repository;
    private CategoryDirectoryService service;

    @BeforeEach
    void setUp() {
        repository = mock(CategoryRepository.class);
        service = new CategoryDirectoryService(repository);
    }

    @Test
    void tree_groupsChildrenUnderTheirParent() {
        Category design = category(1L, null, Category.LEVEL_PRIMARY, "design");
        Category development = category(2L, null, Category.LEVEL_PRIMARY, "development");
        Category systemDesign = category(11L, 1L, Category.LEVEL_SECONDARY, "system-design");
        Category software = category(12L, 2L, Category.LEVEL_SECONDARY, "software-development");
        given(repository.findAllByEnabledTrueOrderBySortOrderAscIdAsc())
                .willReturn(List.of(design, development, systemDesign, software));

        List<CategoryDirectoryService.CategoryBranch> branches = service.tree();

        assertThat(branches).hasSize(2);
        assertThat(branches.get(0).primary().getSlug()).isEqualTo("design");
        assertThat(branches.get(0).children()).extracting(Category::getSlug)
                .containsExactly("system-design");
        assertThat(branches.get(1).children()).extracting(Category::getSlug)
                .containsExactly("software-development");
    }

    @Test
    void tree_keepsPrimaryWithoutChildren() {
        Category engineering = category(4L, null, Category.LEVEL_PRIMARY, "engineering");
        given(repository.findAllByEnabledTrueOrderBySortOrderAscIdAsc()).willReturn(List.of(engineering));

        List<CategoryDirectoryService.CategoryBranch> branches = service.tree();

        assertThat(branches).hasSize(1);
        assertThat(branches.get(0).children()).isEmpty();
    }

    @Test
    void tree_dropsOrphanChildWhoseParentIsMissing() {
        Category orphan = category(99L, 1234L, Category.LEVEL_SECONDARY, "orphan");
        given(repository.findAllByEnabledTrueOrderBySortOrderAscIdAsc()).willReturn(List.of(orphan));

        assertThat(service.tree()).isEmpty();
    }

    @Test
    void requirePrimary_returnsEnabledPrimary() {
        Category design = category(1L, null, Category.LEVEL_PRIMARY, "design");
        given(repository.findBySlugAndParentIdIsNull("design")).willReturn(Optional.of(design));

        assertThat(service.requirePrimary("design").getId()).isEqualTo(1L);
    }

    @Test
    void requirePrimary_rejectsUnknownSlugInsteadOfWideningTheQuery() {
        given(repository.findBySlugAndParentIdIsNull("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.requirePrimary("nope"))
                .isInstanceOf(DomainBadRequestException.class);
    }

    @Test
    void requirePrimary_rejectsDisabledCategory() {
        Category disabled = category(1L, null, Category.LEVEL_PRIMARY, "design");
        disabled.setEnabled(false);
        given(repository.findBySlugAndParentIdIsNull("design")).willReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.requirePrimary("design"))
                .isInstanceOf(DomainBadRequestException.class);
    }

    @Test
    void requireSecondary_rejectsChildOfAnotherParent() {
        given(repository.findBySlugAndParentId("system-design", 2L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireSecondary(2L, "system-design"))
                .isInstanceOf(DomainBadRequestException.class);
    }

    @Test
    void resolveSecondaryIds_returnsEmptyWithoutTouchingRepository() {
        Category design = category(1L, null, Category.LEVEL_PRIMARY, "design");

        assertThat(service.resolveSecondaryIds(design, List.of())).isEmpty();
        verify(repository, never()).findBySlugAndParentId(anyString(), any());
    }

    @Test
    void resolveSecondaryIds_resolvesEverySlugInsideTheSameParent() {
        Category design = category(1L, null, Category.LEVEL_PRIMARY, "design");
        given(repository.findBySlugAndParentId("system-design", 1L))
                .willReturn(Optional.of(category(11L, 1L, Category.LEVEL_SECONDARY, "system-design")));
        given(repository.findBySlugAndParentId("interaction", 1L))
                .willReturn(Optional.of(category(12L, 1L, Category.LEVEL_SECONDARY, "interaction")));

        assertThat(service.resolveSecondaryIds(design, List.of("system-design", "interaction")))
                .containsExactly(11L, 12L);
    }

    @Test
    void findByLegacyDomain_normalizesWireValue() {
        Category projectManagement = category(5L, null, Category.LEVEL_PRIMARY, "project-management");
        setField(projectManagement, "legacyDomain", "PROJECT_MANAGEMENT");
        given(repository.findAllByEnabledTrueOrderBySortOrderAscIdAsc())
                .willReturn(List.of(projectManagement));

        assertThat(service.findByLegacyDomain("project-management")).contains(projectManagement);
        assertThat(service.findByLegacyDomain("PROJECT_MANAGEMENT")).contains(projectManagement);
        assertThat(service.findByLegacyDomain("nope")).isEmpty();
        assertThat(service.findByLegacyDomain(" ")).isEmpty();
    }

    @Test
    void findByIds_returnsEmptyForEmptyInputWithoutQuerying() {
        assertThat(service.findByIds(List.of())).isEmpty();
        verify(repository, never()).findByIdIn(any());
    }

    private static Category category(Long id, Long parentId, short level, String slug) {
        Category category = new Category(parentId, level, slug, 0, "tester");
        setField(category, "id", id);
        return category;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("cannot set " + name, exception);
        }
    }
}
