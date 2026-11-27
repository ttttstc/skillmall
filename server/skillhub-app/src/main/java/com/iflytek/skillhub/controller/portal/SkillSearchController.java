package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.controller.support.DomainQueryParameters;
import com.iflytek.skillhub.controller.support.IncludeOptions;
import com.iflytek.skillhub.domain.category.Category;
import com.iflytek.skillhub.domain.category.CategoryDirectoryService;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.SkillDomain;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.SkillLabelDto;
import com.iflytek.skillhub.dto.SkillSummaryResponse;
import com.iflytek.skillhub.ratelimit.RateLimit;
import com.iflytek.skillhub.service.SkillLabelProjectionService;
import com.iflytek.skillhub.service.SkillSearchAppService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Portal search endpoint that adapts HTTP query parameters to the search
 * application service and visibility scope.
 *
 * <p>Taxonomy filters are resolved here, not in the service: an unknown category slug has to fail
 * as a 400 instead of quietly widening to the whole catalogue, and keeping that rule next to the
 * parameter parsing means every discovery route inherits it.
 */
@RestController
@RequestMapping({"/api/web/skills"})
public class SkillSearchController extends BaseApiController {
    private static final Pattern NON_NEGATIVE_INTEGER = Pattern.compile("\\d+");
    private static final String DEFAULT_SORT = "newest";
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;

    private final SkillSearchAppService skillSearchAppService;
    private final SkillLabelProjectionService skillLabelProjectionService;
    private final CategoryDirectoryService categoryDirectory;

    public SkillSearchController(SkillSearchAppService skillSearchAppService,
                                 SkillLabelProjectionService skillLabelProjectionService,
                                 CategoryDirectoryService categoryDirectory,
                                 ApiResponseFactory responseFactory) {
        super(responseFactory);
        this.skillSearchAppService = skillSearchAppService;
        this.skillLabelProjectionService = skillLabelProjectionService;
        this.categoryDirectory = categoryDirectory;
    }

    @GetMapping
    @RateLimit(category = "search", authenticated = 60, anonymous = 20)
    public ApiResponse<SkillSearchAppService.SearchResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String namespace,
            @RequestParam(name = "label", required = false) java.util.List<String> labels,
            @Parameter(description = "Optional first-level category slug, e.g. design. Takes "
                    + "precedence over the legacy domain parameter when both are supplied.")
            @RequestParam(name = "category", required = false) String category,
            @Parameter(description = "Optional second-level category slugs (repeatable). Several "
                    + "values are matched as OR and must all belong to the same category.")
            @RequestParam(name = "sub", required = false) List<String> subCategories,
            @Parameter(description = "Optional creator user id. Matched against the skill creator, "
                    + "not the current owner.")
            @RequestParam(name = "creator", required = false) String creator,
            @Parameter(description = "Legacy first-level category: DESIGN, DEVELOPMENT, TESTING, "
                    + "ENGINEERING, PROJECT_MANAGEMENT, OTHER")
            @RequestParam(name = "domain", required = false) String domain,
            @Parameter(description = "Optional response expansions. Supported value: labels")
            @RequestParam(name = "include", required = false) List<String> include,
            @Parameter(schema = @Schema(defaultValue = DEFAULT_SORT))
            @RequestParam(required = false) String sort,
            @Parameter(schema = @Schema(type = "integer", defaultValue = "0", minimum = "0"))
            @RequestParam(required = false) String page,
            @Parameter(schema = @Schema(type = "integer", defaultValue = "20", minimum = "1"))
            @RequestParam(required = false) String size,
            @RequestAttribute(value = "userId", required = false) String userId,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {

        boolean includeLabels = IncludeOptions.includesLabels(include);
        TaxonomyFilter filter = resolveTaxonomy(category, subCategories, creator, domain);

        SkillSearchAppService.SearchResponse response = skillSearchAppService.search(
                q,
                namespace,
                normalizeSort(sort),
                parseNonNegativeInt(page, DEFAULT_PAGE),
                parsePositiveInt(size, DEFAULT_SIZE),
                labels,
                filter.domain(),
                filter.categoryId(),
                filter.subCategoryIds(),
                filter.creatorId(),
                userId,
                userNsRoles
        );

        return ok("response.success.read", includeLabels ? withLabels(response) : response);
    }

    /** Resolved taxonomy filter. {@code domain} is dropped once an explicit category is supplied. */
    private record TaxonomyFilter(SkillDomain domain, Long categoryId, List<Long> subCategoryIds,
                                  String creatorId) {
    }

    private TaxonomyFilter resolveTaxonomy(String category, List<String> subCategories,
                                           String creator, String rawDomain) {
        SkillDomain domain = DomainQueryParameters.parse(rawDomain);
        String creatorId = (creator == null || creator.isBlank()) ? null : creator.trim();

        if (category == null || category.isBlank()) {
            if (subCategories != null && !subCategories.isEmpty()) {
                // A second-level slug is only unique inside its parent, so refusing here is
                // unambiguous; silently guessing a parent would return another category's skills.
                throw new DomainBadRequestException("error.skill.category.subWithoutCategory",
                        String.join(",", subCategories));
            }
            return new TaxonomyFilter(domain, null, List.of(), creatorId);
        }

        Category primary = categoryDirectory.requirePrimary(category.trim());
        List<Long> subIds = categoryDirectory.resolveSecondaryIds(primary, subCategories);
        // An explicit category wins over the legacy wire value, so the two never AND together into
        // an empty result the caller cannot explain.
        return new TaxonomyFilter(null, primary.getId(), subIds, creatorId);
    }

    private SkillSearchAppService.SearchResponse withLabels(SkillSearchAppService.SearchResponse response) {
        Map<Long, List<SkillLabelDto>> labelsBySkillId = skillLabelProjectionService.labelsBySkillIds(
                response.items().stream().map(SkillSummaryResponse::id).toList());

        List<SkillSummaryResponse> items = response.items().stream()
                .map(item -> item.withLabels(labelsBySkillId.getOrDefault(item.id(), List.of())))
                .toList();

        return new SkillSearchAppService.SearchResponse(items, response.total(), response.page(), response.size());
    }

    private String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return DEFAULT_SORT;
        }
        return sort.trim();
    }

    private int parseNonNegativeInt(String rawValue, int defaultValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        String normalized = rawValue.trim();
        if (!NON_NEGATIVE_INTEGER.matcher(normalized).matches()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private int parsePositiveInt(String rawValue, int defaultValue) {
        int parsed = parseNonNegativeInt(rawValue, defaultValue);
        return parsed > 0 ? parsed : defaultValue;
    }
}
