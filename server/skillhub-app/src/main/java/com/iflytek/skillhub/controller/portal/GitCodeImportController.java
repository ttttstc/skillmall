package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.SkillImportSource;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.GitCodeImportPublishRequest;
import com.iflytek.skillhub.dto.GitCodeImportPublishResponse;
import com.iflytek.skillhub.dto.GitCodeImportResolveRequest;
import com.iflytek.skillhub.dto.GitCodeImportResolveResponse;
import com.iflytek.skillhub.dto.SkillImportSourceResponse;
import com.iflytek.skillhub.ratelimit.RateLimit;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.iflytek.skillhub.service.GitCodeImportAppService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Import entry points for skills published from a pinned GitCode snapshot.
 *
 * <p>Resolve is read-only and returns the pinned commit; publish requires that commit and the
 * previewed content digest, so publication can never drift onto a newer branch head. Reading the
 * provenance of an already published version is a separate, side-effect free lookup.
 */
@RestController
@RequestMapping("/api/web/skills/import/gitcode")
public class GitCodeImportController extends BaseApiController {

    private final GitCodeImportAppService imports;

    public GitCodeImportController(GitCodeImportAppService imports, ApiResponseFactory responseFactory) {
        super(responseFactory);
        this.imports = imports;
    }

    @PostMapping("/resolve")
    @RateLimit(category = "publish", authenticated = 30, anonymous = 0)
    public ApiResponse<GitCodeImportResolveResponse> resolve(
            @RequestBody GitCodeImportResolveRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        requirePrincipal(principal);
        return ok("response.success.read", imports.resolve(request));
    }

    @PostMapping("/publish")
    @RateLimit(category = "publish", authenticated = 10, anonymous = 0)
    public ApiResponse<GitCodeImportPublishResponse> publish(
            @RequestBody GitCodeImportPublishRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal,
            HttpServletRequest httpRequest) {
        requirePrincipal(principal);
        return ok("response.success.published", imports.publish(
                principal.userId(), principal.platformRoles(), request,
                AuditRequestContext.from(httpRequest)));
    }

    @GetMapping("/versions/{skillVersionId}")
    @RateLimit(category = "publish", authenticated = 60, anonymous = 0)
    public ApiResponse<SkillImportSourceResponse> source(
            @PathVariable Long skillVersionId,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        requirePrincipal(principal);
        SkillImportSource source = imports.source(skillVersionId)
                .orElseThrow(() -> new DomainNotFoundException("error.notFound"));
        return ok("response.success.read", new SkillImportSourceResponse(
                source.provider(), source.repoUrl(), source.repoOwner(), source.repoName(),
                source.branch(), source.commitSha(), source.skillPath(), source.contentDigest(),
                source.fileCount(), source.totalSize(), source.importedBy(), source.createdAt()));
    }

    private static void requirePrincipal(PlatformPrincipal principal) {
        if (principal == null) {
            throw new DomainForbiddenException("error.auth.required");
        }
    }
}
