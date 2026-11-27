package com.iflytek.skillhub.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.iflytek.skillhub.domain.category.Category;
import com.iflytek.skillhub.domain.category.CategoryDirectoryService;
import com.iflytek.skillhub.domain.category.CategoryTranslation;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Page-scoped category resolution.
 *
 * <p>Two properties are load-bearing. First, a listing must not turn into one lookup per card, so a
 * page pre-loads the ids it needs and resolution afterwards is map-only. Second, a missing category
 * degrades to "no chip" — never to an exception that fails the whole listing, which is exactly what
 * happened when the collaborator answered {@code null}.
 */
class SkillCategoryRefTest {

    @Test
    void preload_queriesOnlyTheIdsItDoesNotAlreadyHave() {
        CategoryDirectoryService directory = mock(CategoryDirectoryService.class);
        Category design = category(1L, "design", "设计");
        given(directory.findByIds(List.of(1L, 2L)))
                .willReturn(Map.of(1L, design));

        var pages = new SkillCategoryRef.Pages(directory, "zh").preload(List.of(1L, 2L));

        verify(directory, times(1)).findByIds(List.of(1L, 2L));
        assertThat(pages.resolve(1L)).extracting(SkillCategoryRef::slug).isEqualTo("design");
    }

    @Test
    void resolve_isMapOnlyAfterPreload() {
        CategoryDirectoryService directory = mock(CategoryDirectoryService.class);
        given(directory.findByIds(List.of(1L))).willReturn(Map.of(1L, category(1L, "design", "设计")));

        var pages = new SkillCategoryRef.Pages(directory, "zh").preload(List.of(1L));
        pages.resolve(1L);
        pages.resolve(1L);

        verify(directory, never()).findById(any());
    }

    @Test
    void resolve_fallsBackToSingleLookupWhenNotPreloaded() {
        CategoryDirectoryService directory = mock(CategoryDirectoryService.class);
        given(directory.findById(11L)).willReturn(Optional.of(category(11L, "system-design", "系统设计")));

        var pages = new SkillCategoryRef.Pages(directory, "zh");

        assertThat(pages.resolve(11L)).extracting(SkillCategoryRef::displayName).isEqualTo("系统设计");
    }

    @Test
    void resolve_returnsNullForNullId() {
        var pages = new SkillCategoryRef.Pages(mock(CategoryDirectoryService.class), "zh");
        assertThat(pages.resolve(null)).isNull();
    }

    @Test
    void preload_toleratesCollaboratorReturningNull() {
        CategoryDirectoryService directory = mock(CategoryDirectoryService.class);
        given(directory.findByIds(List.of(1L))).willReturn(null);
        given(directory.findById(1L)).willReturn(null);

        var pages = new SkillCategoryRef.Pages(directory, "zh").preload(List.of(1L));

        assertThat(pages.resolve(1L)).isNull();
    }

    @Test
    void of_carriesIdSlugAndLocalizedName() {
        Category design = category(1L, "design", "设计");

        SkillCategoryRef ref = SkillCategoryRef.of(design, "zh");

        assertThat(ref.id()).isEqualTo(1L);
        assertThat(ref.slug()).isEqualTo("design");
        assertThat(ref.displayName()).isEqualTo("设计");
    }

    @Test
    void of_returnsNullForMissingCategory() {
        assertThat(SkillCategoryRef.of(null, "zh")).isNull();
    }

    @Test
    void preload_handlesEmptyInputWithoutQuerying() {
        CategoryDirectoryService directory = mock(CategoryDirectoryService.class);

        new SkillCategoryRef.Pages(directory, "zh").preload(List.of());

        verify(directory, never()).findByIds(any());
    }

    @Test
    void preload_isIdempotentAcrossRepeatedIds() {
        CategoryDirectoryService directory = mock(CategoryDirectoryService.class);
        Map<Long, Category> loaded = new HashMap<>(Map.of(1L, category(1L, "design", "设计")));
        given(directory.findByIds(List.of(1L))).willReturn(loaded);

        var pages = new SkillCategoryRef.Pages(directory, "zh")
                .preload(List.of(1L))
                .preload(List.of(1L));

        verify(directory, times(1)).findByIds(List.of(1L));
        assertThat(pages.resolve(1L)).isNotNull();
    }

    private static Category category(Long id, String slug, String name) {
        Category category = new Category(null, Category.LEVEL_PRIMARY, slug, 0, "tester");
        try {
            Field field = Category.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(category, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        category.getTranslations().add(new CategoryTranslation(category, "zh", name));
        return category;
    }
}
