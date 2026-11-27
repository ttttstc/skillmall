package com.iflytek.skillhub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.iflytek.skillhub.domain.category.Category;
import com.iflytek.skillhub.domain.category.CategoryDirectoryService;
import com.iflytek.skillhub.domain.category.CategoryTranslation;
import com.iflytek.skillhub.repository.CategoryDirectoryQueryRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * Count semantics for the directory payload.
 *
 * <p>A level-1 node must report what a click on it would return — its own direct skills plus
 * everything filed under its children. Reporting only the direct count shows "0" next to a branch
 * that is visibly full, which is the fastest way to lose trust in a category tree.
 */
class CategoryDirectoryAppServiceTest {

    private CategoryDirectoryService directory;
    private CategoryDirectoryQueryRepository queries;
    private CategoryDirectoryAppService service;

    @BeforeEach
    void setUp() {
        directory = mock(CategoryDirectoryService.class);
        queries = mock(CategoryDirectoryQueryRepository.class);
        service = new CategoryDirectoryAppService(directory, queries);
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void directory_rollsChildCountsIntoTheParent() {
        Category design = category(1L, null, Category.LEVEL_PRIMARY, "design", "设计");
        Category systemDesign = category(11L, 1L, Category.LEVEL_SECONDARY, "system-design", "系统设计");
        given(directory.tree()).willReturn(List.of(
                new CategoryDirectoryService.CategoryBranch(design, List.of(systemDesign))));
        given(queries.countPublicSkills()).willReturn(List.of(
                new CategoryDirectoryQueryRepository.CategoryCount(1L, null, 3L),
                new CategoryDirectoryQueryRepository.CategoryCount(1L, 11L, 5L)));

        var response = service.directory();

        assertThat(response.categories()).hasSize(1);
        var node = response.categories().get(0);
        assertThat(node.count()).isEqualTo(8L);
        assertThat(node.subCategories()).hasSize(1);
        assertThat(node.subCategories().get(0).count()).isEqualTo(5L);
    }

    @Test
    void directory_reportsZeroForPrimaryWithoutDirectSkillsOrChildren() {
        Category engineering = category(4L, null, Category.LEVEL_PRIMARY, "engineering", "工程");
        given(directory.tree()).willReturn(List.of(
                new CategoryDirectoryService.CategoryBranch(engineering, List.of())));
        given(queries.countPublicSkills()).willReturn(List.of());

        var node = service.directory().categories().get(0);

        assertThat(node.count()).isZero();
        assertThat(node.subCategories()).isEmpty();
    }

    @Test
    void directory_resolvesNamesInTheRequestLocale() {
        Category design = category(1L, null, Category.LEVEL_PRIMARY, "design", "设计");
        given(directory.tree()).willReturn(List.of(
                new CategoryDirectoryService.CategoryBranch(design, List.of())));
        given(queries.countPublicSkills()).willReturn(List.of());

        assertThat(service.directory().categories().get(0).displayName()).isEqualTo("设计");

        LocaleContextHolder.setLocale(Locale.KOREAN);
        assertThat(service.directory().categories().get(0).displayName())
                .isEqualTo("设计");
    }

    @Test
    void authors_passesTheQueryAndLimitThrough() {
        given(queries.findPublicAuthors("zhang", 5)).willReturn(List.of(
                new CategoryDirectoryQueryRepository.AuthorRow("u1", "张三", null, 2L)));

        var response = service.authors("zhang", 5);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).userId()).isEqualTo("u1");
        assertThat(response.items().get(0).skillCount()).isEqualTo(2L);
    }

    private static Category category(Long id, Long parentId, short level, String slug, String name) {
        Category category = new Category(parentId, level, slug, 0, "tester");
        setField(category, "id", id);
        category.getTranslations().add(new CategoryTranslation(category, "zh", name));
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
