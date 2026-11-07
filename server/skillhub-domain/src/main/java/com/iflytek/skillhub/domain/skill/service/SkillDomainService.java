package com.iflytek.skillhub.domain.skill.service;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillDomain;
import com.iflytek.skillhub.domain.skill.SkillOwnerAccessPolicy;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes a Skill's first-level category.
 *
 * <p>The category lives on the Skill container, not on a version: re-uploading a package does not
 * silently change which bucket a skill appears in, and an owner can move a skill between
 * categories without publishing anything. Permission to change it is the same rule as every other
 * maintenance write ({@link SkillOwnerAccessPolicy#assertCanManage}).
 */
@Service
public class SkillDomainService {

    private final SkillRepository skills;
    private final SkillOwnerAccessPolicy ownerAccess;

    public SkillDomainService(SkillRepository skills, SkillOwnerAccessPolicy ownerAccess) {
        this.skills = skills;
        this.ownerAccess = ownerAccess;
    }

    @Transactional(readOnly = true)
    public SkillDomain current(Long skillId, String actor, Map<Long, NamespaceRole> roles,
                               Set<String> platformRoles) {
        Skill skill = load(skillId);
        ownerAccess.assertCanManage(skill, actor, roles, platformRoles);
        return skill.getDomain();
    }

    @Transactional
    public SkillDomain change(Long skillId, SkillDomain domain, String actor,
                              Map<Long, NamespaceRole> roles, Set<String> platformRoles) {
        Skill skill = load(skillId);
        // Two independent checks, in this order, matching every other maintenance write
        // (SkillPublishService#assertCanManageLifecycle, SkillVisibilityService, SkillGovernanceService):
        // assertCanWrite is the read-only degradation of an owner who left a TEAM namespace, and
        // assertCanManage is the identity/role rule. Neither subsumes the other — a removed TEAM
        // owner still passes the role check and must be stopped by the first one.
        ownerAccess.assertCanWrite(skill, actor, platformRoles);
        ownerAccess.assertCanManage(skill, actor, roles, platformRoles);
        apply(skill, domain);
        return skill.getDomain();
    }

    /**
     * Applies the category chosen in a publish request inside that request's own transaction, so
     * a skill can never be committed with a category the publisher did not ask for. The publish
     * use case has already authorised the actor, so this does not re-check permissions.
     */
    @Transactional
    public void applyForPublish(Long skillId, SkillDomain domain) {
        if (domain == null) {
            return;
        }
        apply(load(skillId), domain);
    }

    private void apply(Skill skill, SkillDomain domain) {
        skill.setDomain(domain);
        skills.save(skill);
    }

    private Skill load(Long skillId) {
        return skills.findById(skillId)
                .orElseThrow(() -> new DomainNotFoundException("skill.not_found", skillId));
    }
}
