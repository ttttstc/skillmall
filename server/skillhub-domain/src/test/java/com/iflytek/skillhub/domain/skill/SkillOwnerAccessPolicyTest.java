package com.iflytek.skillhub.domain.skill;

import com.iflytek.skillhub.domain.namespace.*;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillOwnerAccessPolicyTest {
    private final NamespaceRepository namespaces = mock(NamespaceRepository.class);
    private final NamespaceMemberRepository members = mock(NamespaceMemberRepository.class);
    private final SkillOwnerAccessPolicy policy = new SkillOwnerAccessPolicy(namespaces, members);
    private final Skill skill = new Skill(1L, "owned", "owner", SkillVisibility.PRIVATE);

    @Test
    void removedTeamOwnerIsReadOnlyAndRejoiningRestoresMaintenance() {
        Namespace namespace = mock(Namespace.class);
        when(namespace.getType()).thenReturn(NamespaceType.TEAM);
        when(namespaces.findById(1L)).thenReturn(Optional.of(namespace));
        when(members.findByNamespaceIdAndUserId(1L, "owner")).thenReturn(Optional.empty());
        assertTrue(policy.isReadOnly(skill, "owner", Set.of()));
        assertThrows(DomainForbiddenException.class, () -> policy.assertCanWrite(skill, "owner", Set.of()));
        when(members.findByNamespaceIdAndUserId(1L, "owner")).thenReturn(Optional.of(mock(NamespaceMember.class)));
        assertFalse(policy.isReadOnly(skill, "owner", Set.of()));
        assertDoesNotThrow(() -> policy.assertCanWrite(skill, "owner", Set.of()));
    }

    @Test
    void globalOwnerDoesNotAcquireTeamRestriction() {
        Namespace namespace = mock(Namespace.class);
        when(namespace.getType()).thenReturn(NamespaceType.GLOBAL);
        when(namespaces.findById(1L)).thenReturn(Optional.of(namespace));
        assertFalse(policy.isReadOnly(skill, "owner", Set.of()));
        verifyNoInteractions(members);
    }

    @Test
    void nonOwnerAndSuperAdminRetainTheirIndependentAuthorizationPaths() {
        assertFalse(policy.isReadOnly(skill, "admin", Set.of()));
        assertFalse(policy.isReadOnly(skill, "owner", Set.of("SUPER_ADMIN")));
        verifyNoInteractions(namespaces, members);
    }

    /**
     * The category write is a maintenance write, so it must use the same identity/role rule as
     * lifecycle and visibility. Non-owners are rejected server-side rather than by hiding a button.
     */
    @Test
    void assertCanManageAllowsOwnerNamespaceAdminAndSuperAdmin() {
        assertDoesNotThrow(() -> policy.assertCanManage(skill, "owner", Map.of(), Set.of()));
        assertDoesNotThrow(() -> policy.assertCanManage(skill, "admin", Map.of(1L, NamespaceRole.ADMIN), Set.of()));
        assertDoesNotThrow(() -> policy.assertCanManage(skill, "ns-owner", Map.of(1L, NamespaceRole.OWNER), Set.of()));
        assertDoesNotThrow(() -> policy.assertCanManage(skill, "staff", Map.of(), Set.of("SUPER_ADMIN")));
    }

    @Test
    void assertCanManageRejectsAStrangerAndAMemberWithoutAnAdminRole() {
        assertThrows(DomainForbiddenException.class,
                () -> policy.assertCanManage(skill, "stranger", Map.of(), Set.of()));
        assertThrows(DomainForbiddenException.class,
                () -> policy.assertCanManage(skill, "member", Map.of(1L, NamespaceRole.MEMBER), Set.of()));
    }

    /**
     * A removed TEAM owner still matches the identity arm of assertCanManage, so the role rule alone
     * cannot stop them: only assertCanWrite does. Both checks are therefore required, in that order,
     * which is why SkillDomainService#change calls them one after the other.
     */
    @Test
    void removedTeamOwnerPassesTheRoleRuleButIsStoppedByTheReadOnlyRule() {
        Namespace namespace = mock(Namespace.class);
        when(namespace.getType()).thenReturn(NamespaceType.TEAM);
        when(namespaces.findById(1L)).thenReturn(Optional.of(namespace));
        when(members.findByNamespaceIdAndUserId(1L, "owner")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> policy.assertCanManage(skill, "owner", Map.of(), Set.of()));
        assertThrows(DomainForbiddenException.class, () -> policy.assertCanWrite(skill, "owner", Set.of()));
    }
}
