package com.iflytek.skillhub.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.iflytek.skillhub.controller.portal.SkillSearchController;
import com.iflytek.skillhub.domain.category.Category;
import com.iflytek.skillhub.domain.category.CategoryDirectoryService;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import com.iflytek.skillhub.service.SkillLabelProjectionService;
import com.iflytek.skillhub.service.SkillSearchAppService;
import java.lang.reflect.Field;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The rules that live in the request layer rather than in the search implementation.
 *
 * <p>Standalone setup on purpose: these are decisions about <em>which</em> call the controller makes,
 * so a full application context (and the database behind it) would only make the test slower and
 * less precise.
 */
class SkillSearchTaxonomyFilterTest {

    private SkillSearchAppService searchAppService;
    private CategoryDirectoryService categoryDirectory;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        searchAppService = mock(SkillSearchAppService.class);
        categoryDirectory = mock(CategoryDirectoryService.class);
        var controller = new SkillSearchController(
                searchAppService,
                mock(SkillLabelProjectionService.class),
                categoryDirectory,
                new ApiResponseFactory(new StaticMessageSource(), Clock.systemUTC(),
                        mock(RequestIdAccessor.class)));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void unknownCategoryIsRejectedAndNeverReachesSearch() {
        given(categoryDirectory.requirePrimary("nope"))
                .willThrow(new DomainBadRequestException("error.skill.category.unknown", "nope"));

        // Standalone setup has no @ControllerAdvice, so the rejection surfaces as the thrown cause
        // rather than as a resolved response. What matters here is that it aborts before search.
        assertThatThrownBy(() -> mockMvc.perform(get("/api/web/skills").param("category", "nope")))
                .hasCauseInstanceOf(DomainBadRequestException.class);
        verify(searchAppService, never()).search(anyString(), any(), anyString(), anyInt(), anyInt(),
                any(), any(), any(), any());
    }

    @Test
    void secondLevelWithoutParentCategoryIsRejected() {
        assertThatThrownBy(() -> mockMvc.perform(get("/api/web/skills").param("sub", "system-design")))
                .hasCauseInstanceOf(DomainBadRequestException.class);
        verify(categoryDirectory, never()).requirePrimary(anyString());
    }

    @Test
    void explicitCategoryWinsOverLegacyDomainAndResolvesChildren() throws Exception {
        Category design = category(1L, "design");
        given(categoryDirectory.requirePrimary("design")).willReturn(design);
        given(categoryDirectory.resolveSecondaryIds(design, List.of("system-design")))
                .willReturn(List.of(11L));

        mockMvc.perform(get("/api/web/skills")
                .param("category", "design")
                .param("sub", "system-design")
                .param("domain", "TESTING")).andReturn();

        verify(searchAppService).search(
                isNull(), isNull(), eq("newest"), eq(0), eq(20), isNull(),
                isNull(),                       // domain dropped: category wins
                eq(1L), eq(List.of(11L)), isNull(),
                isNull(), isNull());
    }

    @Test
    void noTaxonomyFilterKeepsTheOriginalEntryPoint() throws Exception {
        mockMvc.perform(get("/api/web/skills").param("q", "review")).andReturn();

        // The 9-argument overload: unchanged behaviour for callers that did not ask for taxonomy.
        verify(searchAppService).search(
                eq("review"), isNull(), eq("newest"), eq(0), eq(20), isNull(), isNull(),
                isNull(), isNull());
    }

    @Test
    void creatorOnlyUsesTheTaxonomyEntryPoint() throws Exception {
        mockMvc.perform(get("/api/web/skills").param("creator", "u-1")).andReturn();

        verify(searchAppService).search(
                isNull(), isNull(), eq("newest"), eq(0), eq(20), isNull(), isNull(),
                isNull(), eq(List.of()), eq("u-1"), isNull(), isNull());
    }

    @Test
    void legacyDomainAloneStillFilters() throws Exception {
        mockMvc.perform(get("/api/web/skills").param("domain", "TESTING")).andReturn();

        verify(searchAppService).search(
                isNull(), isNull(), eq("newest"), eq(0), eq(20), isNull(),
                eq(com.iflytek.skillhub.domain.skill.SkillDomain.TESTING),
                isNull(), isNull());
    }

    private static Category category(Long id, String slug) {
        Category category = new Category(null, Category.LEVEL_PRIMARY, slug, 10, "tester");
        try {
            Field field = Category.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(category, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        return category;
    }
}
