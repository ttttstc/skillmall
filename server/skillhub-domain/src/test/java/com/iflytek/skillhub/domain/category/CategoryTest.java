package com.iflytek.skillhub.domain.category;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Display-name resolution and shape predicates for a category node.
 *
 * <p>The fallback chain matters more than it looks: a category is created at runtime, so a locale
 * that the administrator did not translate must degrade to a readable name — never to a raw i18n
 * key or an empty chip.
 */
class CategoryTest {

    @Test
    void displayName_prefersRequestedLocale() {
        Category category = category(1L, null, Category.LEVEL_PRIMARY, "design");
        addTranslation(category, "zh", "设计");
        addTranslation(category, "en", "Design");

        assertThat(category.displayName("zh")).isEqualTo("设计");
        assertThat(category.displayName("en")).isEqualTo("Design");
    }

    @Test
    void displayName_fallsBackToChineseThenEnglishThenSlug() {
        Category chineseOnly = category(1L, null, Category.LEVEL_PRIMARY, "design");
        addTranslation(chineseOnly, "zh", "设计");
        assertThat(chineseOnly.displayName("ko")).isEqualTo("设计");

        Category englishOnly = category(2L, null, Category.LEVEL_PRIMARY, "testing");
        addTranslation(englishOnly, "en", "Testing");
        assertThat(englishOnly.displayName("ko")).isEqualTo("Testing");

        Category untranslated = category(3L, null, Category.LEVEL_PRIMARY, "engineering");
        assertThat(untranslated.displayName("ko")).isEqualTo("engineering");
    }

    @Test
    void displayName_matchesPrimarySubtagOfRegionalLocale() {
        Category category = category(1L, null, Category.LEVEL_PRIMARY, "design");
        addTranslation(category, "zh", "设计");

        assertThat(category.displayName("zh-CN")).isEqualTo("设计");
    }

    @Test
    void displayName_ignoresBlankTranslations() {
        Category category = category(1L, null, Category.LEVEL_PRIMARY, "design");
        addTranslation(category, "zh", "   ");
        addTranslation(category, "en", "Design");

        assertThat(category.displayName("zh")).isEqualTo("Design");
    }

    @Test
    void isPrimary_reflectsLevel() {
        assertThat(category(1L, null, Category.LEVEL_PRIMARY, "design").isPrimary()).isTrue();
        assertThat(category(2L, 1L, Category.LEVEL_SECONDARY, "system-design").isPrimary()).isFalse();
    }

    private static Category category(Long id, Long parentId, short level, String slug) {
        Category category = new Category(parentId, level, slug, 0, "tester");
        setField(category, "id", id);
        return category;
    }

    private static void addTranslation(Category category, String locale, String name) {
        category.getTranslations().add(new CategoryTranslation(category, locale, name));
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
