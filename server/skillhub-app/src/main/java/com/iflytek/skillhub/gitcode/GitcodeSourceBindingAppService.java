package com.iflytek.skillhub.gitcode;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.dto.GitcodeSourceBindingResponse;
import com.iflytek.skillhub.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Read model for the GitCode imports of one namespace.
 *
 * <p>Bindings carry repository coordinates and commit shas of skills the namespace already owns, so
 * reading them requires membership — not necessarily an administrator role, because a plain member
 * can already see the imported skills themselves.</p>
 */
@Service
public class GitcodeSourceBindingAppService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    private static final String FORBIDDEN_CODE = "error.forbidden";
    private static final String SUPER_ADMIN = "SUPER_ADMIN";

    private final GitcodeSourceBindingRepository bindings;
    private final NamespaceRepository namespaces;
    private final NamespaceMemberRepository members;

    public GitcodeSourceBindingAppService(GitcodeSourceBindingRepository bindings,
                                          NamespaceRepository namespaces,
                                          NamespaceMemberRepository members) {
        this.bindings = bindings;
        this.namespaces = namespaces;
        this.members = members;
    }

    @Transactional(readOnly = true)
    public PageResponse<GitcodeSourceBindingResponse> list(String namespaceSlug, int page, int size,
                                                           String userId, Set<String> platformRoles) {
        Namespace namespace = namespaces.findBySlug(namespaceSlug)
                .orElseThrow(() -> new DomainNotFoundException("error.namespace.slug.notFound", namespaceSlug));
        boolean superAdmin = platformRoles != null && platformRoles.contains(SUPER_ADMIN);
        if (!superAdmin) {
            members.findByNamespaceIdAndUserId(namespace.getId(), userId)
                    .orElseThrow(() -> new DomainForbiddenException(FORBIDDEN_CODE));
        }
        int requestedPage = Math.max(0, page);
        int requestedSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Page<GitcodeSourceBinding> result = bindings.findByNamespaceIdOrderByLastImportedAtDescIdDesc(
                namespace.getId(), PageRequest.of(requestedPage, requestedSize));
        List<GitcodeSourceBindingResponse> items = result.getContent().stream()
                .map(GitcodeSourceBindingAppService::response)
                .toList();
        return new PageResponse<>(items, result.getTotalElements(), result.getNumber(), result.getSize());
    }

    private static GitcodeSourceBindingResponse response(GitcodeSourceBinding binding) {
        SkillVisibility visibility = binding.getVisibility();
        return new GitcodeSourceBindingResponse(
                binding.getOwnerLogin(),
                binding.getRepositoryName(),
                binding.getSkillPath(),
                binding.getGitRef(),
                binding.getRefType(),
                binding.getCommitSha(),
                binding.getLinkType(),
                visibility == null ? null : visibility.name(),
                binding.getLastImportedAt(),
                binding.getSkillId(),
                binding.getSkillVersionId());
    }
}
