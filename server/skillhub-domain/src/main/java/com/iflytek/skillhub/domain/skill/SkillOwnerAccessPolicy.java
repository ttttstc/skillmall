package com.iflytek.skillhub.domain.skill;

import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceType;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Separates a current owner's read access from permission to maintain a TEAM skill. */
@Component
public class SkillOwnerAccessPolicy {
    private final NamespaceRepository namespaces;
    private final NamespaceMemberRepository members;

    public SkillOwnerAccessPolicy(NamespaceRepository namespaces, NamespaceMemberRepository members) {
        this.namespaces = namespaces;
        this.members = members;
    }

    public boolean isReadOnly(Skill skill, String actor, Set<String> platformRoles) {
        if (actor == null || !actor.equals(skill.getOwnerId())
                || platformRoles != null && platformRoles.contains("SUPER_ADMIN")) {
            return false;
        }
        var namespace = namespaces.findById(skill.getNamespaceId())
                .orElseThrow(() -> new DomainNotFoundException("namespace.not_found", skill.getNamespaceId()));
        return namespace.getType() == NamespaceType.TEAM
                && members.findByNamespaceIdAndUserId(skill.getNamespaceId(), actor).isEmpty();
    }

    public void assertCanWrite(Skill skill, String actor, Set<String> platformRoles) {
        if (isReadOnly(skill, actor, platformRoles)) {
            throw new DomainForbiddenException("error.skill.owner.readOnly");
        }
    }

    /**
     * Permission to maintain a Skill: its current owner, a namespace ADMIN/OWNER, or a platform
     * SUPER_ADMIN. Visibility and category writes call this method; the lifecycle services still
     * carry an equivalent inline check ({@code SkillPublishService#assertCanManageLifecycle}), so a
     * change to this rule has to be mirrored there.
     */
    public void assertCanManage(Skill skill, String actor, Map<Long, NamespaceRole> roles,
                                Set<String> platformRoles) {
        NamespaceRole role = roles == null ? null : roles.get(skill.getNamespaceId());
        if (!Objects.equals(actor, skill.getOwnerId()) && role != NamespaceRole.OWNER
                && role != NamespaceRole.ADMIN
                && (platformRoles == null || !platformRoles.contains("SUPER_ADMIN"))) {
            throw new DomainForbiddenException("error.skill.lifecycle.noPermission");
        }
    }
}
