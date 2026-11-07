package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.skill.SkillDomain;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.ratelimit.RateLimit;
import com.iflytek.skillhub.service.SkillDomainAppService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads and updates the first-level category of a Skill.
 *
 * <p>The read is owner/manager scoped because it exists to pre-fill the edit form; the write is
 * the "edit the category without publishing a new package" path. Public discovery never needs
 * these endpoints: it filters by {@code domain} on the list/search/home endpoints instead.
 */
@RestController
@RequestMapping({"/api/v1/skills", "/api/web/skills"})
public class SkillDomainController extends BaseApiController {

    private final SkillDomainAppService service;

    public SkillDomainController(SkillDomainAppService service, ApiResponseFactory responses) {
        super(responses);
        this.service = service;
    }

    @Operation(operationId = "getSkillDomain", summary = "Current first-level category of a Skill")
    @GetMapping("/{namespace}/{slug}/domain")
    public ApiResponse<SkillDomainAppService.Result> current(
            @PathVariable String namespace,
            @PathVariable String slug,
            @RequestAttribute("userId") String actor,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> roles) {
        return ok("response.success.read", service.current(namespace, slug, actor, roles));
    }

    @Operation(operationId = "changeSkillDomain", summary = "Move a Skill to another first-level category")
    @PostMapping("/{namespace}/{slug}/domain")
    @RateLimit(category = "publish", authenticated = 30, anonymous = 0)
    public ApiResponse<SkillDomainAppService.Result> change(
            @PathVariable String namespace,
            @PathVariable String slug,
            @Valid @RequestBody ChangeDomainRequest request,
            @RequestAttribute("userId") String actor,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> roles) {
        return ok("response.success.updated", service.change(namespace, slug, request.domain(), actor, roles));
    }

    public record ChangeDomainRequest(@NotNull SkillDomain domain) {
    }
}
