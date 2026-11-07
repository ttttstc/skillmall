package com.iflytek.skillhub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iflytek.skillhub.auth.rbac.RbacService;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillDomain;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.service.SkillDomainService;
import com.iflytek.skillhub.domain.skill.service.SkillSlugResolutionService;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class SkillDomainAppServiceTest {

    private final NamespaceRepository namespaces = mock(NamespaceRepository.class);
    private final SkillSlugResolutionService resolver = mock(SkillSlugResolutionService.class);
    private final SkillDomainService domains = mock(SkillDomainService.class);
    private final RbacService rbac = mock(RbacService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final RequestIdAccessor requestIds = mock(RequestIdAccessor.class);
    private final SkillDomainAppService service =
            new SkillDomainAppService(namespaces, resolver, domains, rbac, audit, requestIds);

    /**
     * The audit row is the only record of what a category change replaced. `SkillDomainService`
     * re-loads the Skill by id inside the same transaction, so it hands back the *same* managed
     * instance the app service already holds; reading the old value after the write would silently
     * record `previousDomain == targetDomain`. This test pins the previous value by making the
     * domain-service stub mutate the shared instance exactly like the real one does.
     */
    @Test
    void changeRecordsTheCategoryThatWasReplacedNotTheOneJustWritten() {
        Skill skill = skill(7L, 11L, "demo-skill", "owner-1", SkillDomain.DESIGN);
        stubResolve(skill);

        when(domains.change(eq(11L), eq(SkillDomain.TESTING), eq("owner-1"), anyMap(), anySet()))
                .thenAnswer(invocation -> {
                    // Same persistence-context identity: this is the instance `resolve` returned.
                    skill.setDomain(SkillDomain.TESTING);
                    return SkillDomain.TESTING;
                });
        when(requestIds.current()).thenReturn("req-42");

        SkillDomainAppService.Result result = service.change(
                "team", "demo-skill", SkillDomain.TESTING, "owner-1", Map.of(7L, NamespaceRole.OWNER));

        assertThat(result.domain()).isEqualTo(SkillDomain.TESTING);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq("owner-1"), eq("SKILL_DOMAIN_CHANGED"), eq("SKILL"), eq(11L),
                eq("req-42"), isNull(), isNull(), detail.capture());

        assertThat(detail.getValue())
                .contains("\"previousDomain\":\"DESIGN\"")
                .contains("\"targetDomain\":\"TESTING\"");
        // The discriminating assertion: a regression that reads the value after the write would
        // produce "previousDomain":"TESTING" and pass everything above except this.
        assertThat(detail.getValue()).doesNotContain("\"previousDomain\":\"TESTING\"");
    }

    @Test
    void changeNormalizesANullCategoryToTheDocumentedDefault() {
        Skill skill = skill(7L, 11L, "demo-skill", "owner-1", null);
        stubResolve(skill);

        when(domains.change(eq(11L), eq(SkillDomain.OTHER), eq("owner-1"), anyMap(), anySet()))
                .thenAnswer(invocation -> {
                    skill.setDomain(null);
                    return SkillDomain.DEFAULT;
                });

        SkillDomainAppService.Result result = service.change(
                "team", "demo-skill", SkillDomain.OTHER, "owner-1", Map.of(7L, NamespaceRole.OWNER));

        assertThat(result.domain()).isEqualTo(SkillDomain.OTHER);
    }

    @Test
    void changeAcceptsAnAtPrefixedNamespaceLikeTheOtherPortalUseCases() {
        Skill skill = skill(7L, 11L, "demo-skill", "owner-1", SkillDomain.OTHER);
        stubResolve(skill);
        when(domains.change(eq(11L), eq(SkillDomain.ENGINEERING), eq("owner-1"), anyMap(), anySet()))
                .thenReturn(SkillDomain.ENGINEERING);

        SkillDomainAppService.Result result = service.change(
                "@team", "demo-skill", SkillDomain.ENGINEERING, "owner-1", Map.of());

        assertThat(result.domain()).isEqualTo(SkillDomain.ENGINEERING);
    }

    private void stubResolve(Skill skill) {
        Namespace namespace = new Namespace("team", "Team", "owner-1");
        ReflectionTestUtils.setField(namespace, "id", 7L);
        when(namespaces.findBySlug("team")).thenReturn(Optional.of(namespace));
        when(resolver.resolve(eq(7L), eq("demo-skill"), eq("owner-1"),
                eq(SkillSlugResolutionService.Preference.CURRENT_USER))).thenReturn(skill);
        when(rbac.getUserRoleCodes(any())).thenReturn(Set.of());
    }

    private static Skill skill(Long namespaceId, Long id, String slug, String ownerId, SkillDomain domain) {
        Skill skill = new Skill(namespaceId, slug, ownerId, SkillVisibility.PUBLIC);
        ReflectionTestUtils.setField(skill, "id", id);
        skill.setDomain(domain);
        return skill;
    }
}
