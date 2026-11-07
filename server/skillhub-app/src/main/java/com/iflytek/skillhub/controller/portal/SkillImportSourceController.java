package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.SkillImportSource;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.SkillImportSourceResponse;
import com.iflytek.skillhub.ratelimit.RateLimit;
import com.iflytek.skillhub.service.GitCodeImportAppService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Provenance lookup for one published skill version.
 *
 * <p>The route is addressed by the same coordinates as the version detail endpoint and resolves the
 * version through that endpoint's visibility-checked query first, so repository, commit, path and
 * importer are only readable by callers who may already inspect the version.
 */
@RestController
@RequestMapping("/api/web/skills")
public class SkillImportSourceController extends BaseApiController {

    private final GitCodeImportAppService imports;

    public SkillImportSourceController(GitCodeImportAppService imports, ApiResponseFactory responseFactory) {
        super(responseFactory);
        this.imports = imports;
    }

    @GetMapping("/{namespace}/{slug}/versions/{version}/import-source")
    @RateLimit(category = "publish", authenticated = 60, anonymous = 0)
    public ApiResponse<SkillImportSourceResponse> source(
            @PathVariable String namespace,
            @PathVariable String slug,
            @PathVariable String version,
            @RequestAttribute(value = "userId", required = false) String userId,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        if (principal == null) {
            throw new DomainForbiddenException("error.auth.required");
        }
        SkillImportSource source = imports.source(namespace, slug, version, userId, userNsRoles)
                .orElseThrow(() -> new DomainNotFoundException("error.notFound"));
        return ok("response.success.read", new SkillImportSourceResponse(
                source.provider(), source.repoUrl(), source.repoOwner(), source.repoName(),
                source.branch(), source.commitSha(), source.skillPath(), source.contentDigest(),
                source.fileCount(), source.totalSize(), source.importedBy(), source.createdAt()));
    }
}
