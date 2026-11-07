package com.iflytek.skillhub.service;

import com.iflytek.skillhub.auth.rbac.RbacService;
import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillDomain;
import com.iflytek.skillhub.domain.skill.service.SkillDomainService;
import com.iflytek.skillhub.domain.skill.service.SkillSlugResolutionService;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HTTP-facing use case for a Skill's first-level category.
 *
 * <p>Resolution and permission checks follow the visibility use case on purpose: the category is
 * container metadata like the audience, so "who may change it" must not be a second policy.
 */
@Service
public class SkillDomainAppService {

    private final NamespaceRepository namespaces;
    private final SkillSlugResolutionService resolver;
    private final SkillDomainService domains;
    private final RbacService rbac;
    private final AuditLogService audit;
    private final RequestIdAccessor requestIds;

    public SkillDomainAppService(NamespaceRepository namespaces,
                                 SkillSlugResolutionService resolver,
                                 SkillDomainService domains,
                                 RbacService rbac,
                                 AuditLogService audit,
                                 RequestIdAccessor requestIds) {
        this.namespaces = namespaces;
        this.resolver = resolver;
        this.domains = domains;
        this.rbac = rbac;
        this.audit = audit;
        this.requestIds = requestIds;
    }

    @Transactional(readOnly = true)
    public Result current(String namespace, String slug, String actor, Map<Long, NamespaceRole> roles) {
        Skill skill = resolve(namespace, slug, actor);
        SkillDomain domain = domains.current(skill.getId(), actor,
                roles == null ? Map.of() : roles, rbac.getUserRoleCodes(actor));
        return new Result(domain);
    }

    @Transactional
    public Result change(String namespace, String slug, SkillDomain domain, String actor,
                         Map<Long, NamespaceRole> roles) {
        Skill skill = resolve(namespace, slug, actor);
        // Snapshot the current category *before* the write. `domains.change` re-loads the Skill by
        // id inside this same transaction, which hands back the very same managed instance that
        // `resolve` returned — so reading `skill.getDomain()` afterwards would report the new value
        // as the previous one and the audit trail would lose what actually changed.
        SkillDomain previous = skill.getDomain();
        SkillDomain updated = domains.change(skill.getId(), domain, actor,
                roles == null ? Map.of() : roles, rbac.getUserRoleCodes(actor));
        audit.record(actor, "SKILL_DOMAIN_CHANGED", "SKILL", skill.getId(), requestIds.current(),
                null, null,
                AuditDetail.builder()
                        .put("previousDomain", previous.name())
                        .put("targetDomain", updated.name())
                        .build());
        return new Result(updated);
    }

    private Skill resolve(String namespace, String slug, String actor) {
        String cleanNamespace = namespace.startsWith("@") ? namespace.substring(1) : namespace;
        var ns = namespaces.findBySlug(cleanNamespace)
                .orElseThrow(() -> new DomainNotFoundException("namespace.not_found", cleanNamespace));
        return resolver.resolve(ns.getId(), slug, actor, SkillSlugResolutionService.Preference.CURRENT_USER);
    }

    public record Result(SkillDomain domain) {
    }
}
