package com.iflytek.skillhub.controller;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.GitcodeImportRequest;
import com.iflytek.skillhub.dto.GitcodeInspectRequest;
import com.iflytek.skillhub.dto.GitcodeInspectResponse;
import com.iflytek.skillhub.dto.GitcodeSourceBindingResponse;
import com.iflytek.skillhub.dto.PageResponse;
import com.iflytek.skillhub.dto.PublishResponse;
import com.iflytek.skillhub.gitcode.GitcodeImportAppService;
import com.iflytek.skillhub.gitcode.GitcodeSourceBindingAppService;
import com.iflytek.skillhub.metrics.SkillHubMetrics;
import com.iflytek.skillhub.ratelimit.RateLimit;
import com.iflytek.skillhub.service.AuditRequestContext;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * GitCode code-repository import endpoints.
 *
 * <p>The controller only shapes transport: it extracts the trusted principal, keeps the publish
 * response identical to the archive upload endpoint, and leaves URL parsing, revision resolution,
 * scanning, and publishing to the application services.</p>
 */
@RestController
public class GitcodeImportController extends BaseApiController {

    private final GitcodeImportAppService imports;
    private final GitcodeSourceBindingAppService bindings;
    private final SkillHubMetrics metrics;

    public GitcodeImportController(ApiResponseFactory responseFactory, GitcodeImportAppService imports,
                                   GitcodeSourceBindingAppService bindings, SkillHubMetrics metrics) {
        super(responseFactory);
        this.imports = imports;
        this.bindings = bindings;
        this.metrics = metrics;
    }

    @PostMapping({"/api/v1/skills/gitcode/inspect", "/api/web/skills/gitcode/inspect"})
    @RateLimit(category = "gitcode", authenticated = 30, anonymous = 0)
    @Operation(summary = "预览 GitCode 代码仓中的 Skill 目录")
    public ApiResponse<GitcodeInspectResponse> inspect(@RequestBody GitcodeInspectRequest body) {
        return ok("response.success.read", imports.inspect(body.repoUrl(), body.ref(), body.path()));
    }

    @PostMapping({"/api/v1/skills/{namespace}/import/gitcode", "/api/web/skills/{namespace}/import/gitcode"})
    @RateLimit(category = "gitcode", authenticated = 10, anonymous = 0)
    @Operation(summary = "从 GitCode 代码仓导入并发布 Skill")
    public ApiResponse<PublishResponse> importFromRepository(
            @PathVariable String namespace,
            @RequestBody GitcodeImportRequest body,
            @AuthenticationPrincipal PlatformPrincipal principal,
            HttpServletRequest request) {
        if (principal == null) {
            throw new DomainForbiddenException("error.auth.required");
        }
        PublishResponse response = imports.importSkill(namespace, body.repoUrl(), body.ref(), body.path(),
                parseVisibility(body.visibility()), Boolean.TRUE.equals(body.confirmWarnings()),
                body.displayName(), body.changelog(), body.version(), principal.userId(), principal.platformRoles(),
                AuditRequestContext.from(request));
        metrics.incrementSkillPublish(namespace, response.status());
        return ok("response.success.published", response);
    }

    @GetMapping({"/api/v1/namespaces/{namespaceSlug}/gitcode-sources",
            "/api/web/namespaces/{namespaceSlug}/gitcode-sources"})
    @Operation(summary = "查看命名空间的 GitCode 来源绑定")
    public ApiResponse<PageResponse<GitcodeSourceBindingResponse>> listBindings(
            @PathVariable String namespaceSlug,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        if (principal == null) {
            throw new DomainForbiddenException("error.auth.required");
        }
        return ok("response.success.read",
                bindings.list(namespaceSlug, page, size, principal.userId(), principal.platformRoles()));
    }

    private static SkillVisibility parseVisibility(String rawVisibility) {
        if (rawVisibility == null || rawVisibility.isBlank()) {
            throw new DomainBadRequestException("error.badRequest");
        }
        try {
            return SkillVisibility.valueOf(rawVisibility.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new DomainBadRequestException("error.badRequest");
        }
    }
}
