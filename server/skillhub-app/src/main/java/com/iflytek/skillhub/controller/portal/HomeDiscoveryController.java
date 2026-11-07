package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.controller.support.DomainQueryParameters;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.HomeRankingResponse;
import com.iflytek.skillhub.dto.HomeSkillListResponse;
import com.iflytek.skillhub.ratelimit.RateLimit;
import com.iflytek.skillhub.service.HomeDiscoveryAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Home page discovery endpoints: generic recommendations and the three rankings.
 *
 * <p>Recommendations are a random draw, not a personalisation signal. Rankings are separate
 * endpoints because they answer different questions (recent window vs cumulative vs first
 * publication) and must not be collapsed into one "popular" list.
 */
@RestController
@Tag(name = "Home discovery")
@RequestMapping({"/api/web/home"})
public class HomeDiscoveryController extends BaseApiController {

    private final HomeDiscoveryAppService appService;

    public HomeDiscoveryController(HomeDiscoveryAppService appService,
                                   ApiResponseFactory responseFactory) {
        super(responseFactory);
        this.appService = appService;
    }

    @GetMapping("/recommendations")
    @Operation(
            operationId = "homeRecommendations",
            summary = "Generic (non-personalised) recommendations; pass current ids to reshuffle"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Recommendation row returned")
    @RateLimit(category = "search", authenticated = 60, anonymous = 20)
    public ApiResponse<HomeSkillListResponse> recommendations(
            @Parameter(schema = @Schema(type = "integer", defaultValue = "6", minimum = "1"))
            @RequestParam(defaultValue = "6") int size,
            @Parameter(description = "Skill ids already on screen; they are skipped so the row rotates")
            @RequestParam(name = "exclude", required = false) List<Long> exclude,
            @Parameter(description = "Optional first-level category: DESIGN, DEVELOPMENT, TESTING, "
                    + "ENGINEERING, PROJECT_MANAGEMENT, OTHER")
            @RequestParam(name = "domain", required = false) String domain,
            @RequestAttribute(value = "userId", required = false) String userId,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {
        return ok("response.success.read", appService.recommendations(
                size, exclude, DomainQueryParameters.parse(domain), userId, userNsRoles));
    }

    @GetMapping("/rankings")
    @Operation(
            operationId = "homeRankings",
            summary = "One ranking: recent-popular (7-day downloads), most-downloaded (cumulative), newest (first published)"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Ranked Skill summaries returned")
    @RateLimit(category = "search", authenticated = 60, anonymous = 20)
    public ApiResponse<HomeRankingResponse> rankings(
            @Parameter(schema = @Schema(
                    defaultValue = "recent-popular",
                    allowableValues = {"recent-popular", "most-downloaded", "newest"}))
            @RequestParam(defaultValue = "recent-popular") String type,
            @Parameter(schema = @Schema(type = "integer", defaultValue = "10", minimum = "1"))
            @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "Optional first-level category: DESIGN, DEVELOPMENT, TESTING, "
                    + "ENGINEERING, PROJECT_MANAGEMENT, OTHER")
            @RequestParam(name = "domain", required = false) String domain,
            @RequestAttribute(value = "userId", required = false) String userId,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {
        return ok("response.success.read", appService.ranking(
                type, size, DomainQueryParameters.parse(domain), userId, userNsRoles));
    }
}
