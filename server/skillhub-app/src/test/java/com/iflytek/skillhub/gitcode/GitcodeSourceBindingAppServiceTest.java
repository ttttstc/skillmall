package com.iflytek.skillhub.gitcode;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMember;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.dto.GitcodeSourceBindingResponse;
import com.iflytek.skillhub.dto.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class GitcodeSourceBindingAppServiceTest {

    private static final Instant IMPORTED_AT = Instant.parse("2026-11-12T00:00:00Z");

    private final GitcodeSourceBindingRepository bindings = Mockito.mock(GitcodeSourceBindingRepository.class);
    private final NamespaceRepository namespaces = Mockito.mock(NamespaceRepository.class);
    private final NamespaceMemberRepository members = Mockito.mock(NamespaceMemberRepository.class);
    private final Namespace namespace = Mockito.mock(Namespace.class);
    private final GitcodeSourceBindingAppService service =
            new GitcodeSourceBindingAppService(bindings, namespaces, members);

    @BeforeEach
    void stubNamespace() {
        Mockito.when(namespaces.findBySlug("team")).thenReturn(Optional.of(namespace));
        Mockito.when(namespace.getId()).thenReturn(7L);
    }

    @Test
    void listsBindingsForNamespaceMembers() {
        Mockito.when(members.findByNamespaceIdAndUserId(7L, "user-1"))
                .thenReturn(Optional.of(Mockito.mock(NamespaceMember.class)));
        GitcodeSourceBinding binding = binding();
        Mockito.when(bindings.findByNamespaceIdOrderByLastImportedAtDescIdDesc(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(binding), PageRequest.of(0, 20), 1));

        PageResponse<GitcodeSourceBindingResponse> page =
                service.list("team", 0, 20, "user-1", Set.of());

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.owner()).isEqualTo("acme");
            assertThat(item.repository()).isEqualTo("skills");
            assertThat(item.path()).isEqualTo("skills/foo");
            assertThat(item.ref()).isEqualTo("main");
            assertThat(item.refType()).isEqualTo(GitcodeRefType.BRANCH);
            assertThat(item.commitSha()).isEqualTo("sha-main");
            assertThat(item.linkType()).isEqualTo(GitcodeLinkType.ONE_SHOT);
            assertThat(item.visibility()).isEqualTo("PUBLIC");
            assertThat(item.lastImportedAt()).isEqualTo(IMPORTED_AT);
            assertThat(item.skillId()).isEqualTo(41L);
            assertThat(item.skillVersionId()).isEqualTo(31L);
        });
    }

    @Test
    void clampsPageAndSizeToTheSupportedRange() {
        Mockito.when(members.findByNamespaceIdAndUserId(7L, "user-1"))
                .thenReturn(Optional.of(Mockito.mock(NamespaceMember.class)));
        Mockito.when(bindings.findByNamespaceIdOrderByLastImportedAtDescIdDesc(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        service.list("team", -3, 500, "user-1", Set.of());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        Mockito.verify(bindings).findByNamespaceIdOrderByLastImportedAtDescIdDesc(eq(7L), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(GitcodeSourceBindingAppService.MAX_PAGE_SIZE);
    }

    @Test
    void rejectsReadersOutsideTheNamespace() {
        Mockito.when(members.findByNamespaceIdAndUserId(7L, "stranger")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list("team", 0, 20, "stranger", Set.of()))
                .isInstanceOf(DomainForbiddenException.class);
        Mockito.verify(bindings, Mockito.never())
                .findByNamespaceIdOrderByLastImportedAtDescIdDesc(any(), any());
    }

    @Test
    void allowsPlatformSuperAdmins() {
        Mockito.when(bindings.findByNamespaceIdOrderByLastImportedAtDescIdDesc(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service.list("team", 0, 20, "admin-1", Set.of("SUPER_ADMIN"));

        Mockito.verifyNoInteractions(members);
    }

    @Test
    void rejectsUnknownNamespaces() {
        Mockito.when(namespaces.findBySlug("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list("missing", 0, 20, "user-1", Set.of()))
                .isInstanceOf(DomainNotFoundException.class);
    }

    private static GitcodeSourceBinding binding() {
        GitcodeSourceBinding binding = new GitcodeSourceBinding(7L, "acme", "skills", "skills/foo", "main",
                GitcodeRefType.BRANCH, "sha-main", GitcodeLinkType.ONE_SHOT, SkillVisibility.PUBLIC, "user-1",
                IMPORTED_AT);
        binding.recordImport("main", GitcodeRefType.BRANCH, "sha-main", SkillVisibility.PUBLIC, 41L, 31L,
                IMPORTED_AT);
        return binding;
    }
}
