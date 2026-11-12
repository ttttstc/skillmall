package com.iflytek.skillhub.gitcode;

import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.dto.GitcodeInspectResponse;
import com.iflytek.skillhub.dto.PublishResponse;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.iflytek.skillhub.service.SkillPublishAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

class GitcodeImportAppServiceTest {

    private static final String ROOT_URL = "https://gitcode.com/acme/skills";
    private static final String BRANCH_URL = "https://gitcode.com/acme/skills/tree/main/skills/foo";
    private static final String TAG_URL = "https://gitcode.com/acme/skills/tree/v1.2.3/skills/foo";
    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");
    private static final AuditRequestContext CONTEXT = new AuditRequestContext("10.0.0.1", "junit");

    private final GitcodeClient client = Mockito.mock(GitcodeClient.class);
    private final GitcodeSkillScanner scanner = Mockito.mock(GitcodeSkillScanner.class);
    private final SkillPublishAppService publish = Mockito.mock(SkillPublishAppService.class);
    private final GitcodeSourceBindingRepository bindings = Mockito.mock(GitcodeSourceBindingRepository.class);
    private final NamespaceRepository namespaces = Mockito.mock(NamespaceRepository.class);
    private final SkillRepository skills = Mockito.mock(SkillRepository.class);
    private final SkillVersionRepository versions = Mockito.mock(SkillVersionRepository.class);
    private final AuditLogService audit = Mockito.mock(AuditLogService.class);
    private final RequestIdAccessor requestIds = Mockito.mock(RequestIdAccessor.class);
    private final Namespace namespace = Mockito.mock(Namespace.class);
    private final GitcodeImportAppService service = new GitcodeImportAppService(
            new GitcodeUrlParser(), client, scanner, publish, bindings, namespaces, skills, versions, audit,
            requestIds, Clock.fixed(NOW, ZoneOffset.UTC));

    private SkillVersion publishedVersion;
    private SkillPublishService.PublishResult publishedResult;

    @BeforeEach
    void stubRepositoryAccess() {
        Mockito.when(client.listBranches(any(), eq("acme"), eq("skills")))
                .thenReturn(List.of(new GitcodeClient.Branch("main", "sha-main", true),
                        new GitcodeClient.Branch("dev", "sha-dev", false)));
        Mockito.when(client.listTags(any(), eq("acme"), eq("skills")))
                .thenReturn(List.of(new GitcodeClient.TagRef("v1.2.3", "sha-tag")));
        Mockito.when(namespaces.findBySlug("team")).thenReturn(Optional.of(namespace));
        Mockito.when(namespace.getId()).thenReturn(7L);
        Mockito.when(requestIds.current()).thenReturn("req-1");
        // Built here rather than inside a thenReturn(...) argument: stubbing the version mock while
        // another stubbing is in progress leaves Mockito in "unfinished stubbing" state.
        publishedVersion = Mockito.mock(SkillVersion.class);
        Mockito.when(publishedVersion.getId()).thenReturn(31L);
        Mockito.when(publishedVersion.getSkillId()).thenReturn(41L);
        Mockito.when(publishedVersion.getVersion()).thenReturn("1.0.0");
        Mockito.when(publishedVersion.getStatus()).thenReturn(SkillVersionStatus.PUBLISHED);
        Mockito.when(publishedVersion.getFileCount()).thenReturn(1);
        Mockito.when(publishedVersion.getTotalSize()).thenReturn(60L);
        publishedResult = new SkillPublishService.PublishResult(41L, "demo-skill", publishedVersion);
    }

    @Test
    void publishesOnceAndRecordsTheImportBinding() {
        stubSkillAtPath("skills/foo", "1.0.0");
        Mockito.when(publish.publish(anyString(), anyList(), anyString(), any(), anySet(), anyBoolean(), any(),
                any(), any(), any(), any(), any())).thenReturn(publishedResult);

        PublishResponse response = service.importSkill("team", BRANCH_URL, null, "skills/foo",
                SkillVisibility.PUBLIC, false, "Display", "note", null, "user-1", Set.of("MEMBER"), CONTEXT);

        assertThat(response.skillId()).isEqualTo(41L);
        assertThat(response.namespace()).isEqualTo("team");
        assertThat(response.slug()).isEqualTo("demo-skill");
        assertThat(response.version()).isEqualTo("1.0.0");
        assertThat(response.status()).isEqualTo("PUBLISHED");
        assertThat(response.fileCount()).isEqualTo(1);
        assertThat(response.totalSize()).isEqualTo(60L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PackageEntry>> entries = ArgumentCaptor.forClass(List.class);
        Mockito.verify(publish, Mockito.times(1)).publish(eq("team"), entries.capture(), eq("user-1"),
                eq(SkillVisibility.PUBLIC), eq(Set.of("MEMBER")), eq(false),
                eq(SkillPublishAppService.AvatarAction.KEEP), isNull(), isNull(), eq("Display"), eq("note"),
                eq(CONTEXT));
        assertThat(entries.getValue()).extracting(PackageEntry::path).containsExactly("SKILL.md");

        ArgumentCaptor<GitcodeSourceBinding> binding = ArgumentCaptor.forClass(GitcodeSourceBinding.class);
        Mockito.verify(bindings).save(binding.capture());
        GitcodeSourceBinding saved = binding.getValue();
        assertThat(saved.getNamespaceId()).isEqualTo(7L);
        assertThat(saved.getOwnerLogin()).isEqualTo("acme");
        assertThat(saved.getRepositoryName()).isEqualTo("skills");
        assertThat(saved.getSkillPath()).isEqualTo("skills/foo");
        assertThat(saved.getGitRef()).isEqualTo("main");
        assertThat(saved.getRefType()).isEqualTo(GitcodeRefType.BRANCH);
        assertThat(saved.getCommitSha()).isEqualTo("sha-main");
        assertThat(saved.getLinkType()).isEqualTo(GitcodeLinkType.ONE_SHOT);
        assertThat(saved.getVisibility()).isEqualTo(SkillVisibility.PUBLIC);
        assertThat(saved.getCreatedBy()).isEqualTo("user-1");
        assertThat(saved.getSkillId()).isEqualTo(41L);
        assertThat(saved.getSkillVersionId()).isEqualTo(31L);
        assertThat(saved.getLastImportedAt()).isEqualTo(NOW);

        Mockito.verify(audit).record(eq("user-1"), eq(GitcodeImportAppService.AUDIT_ACTION),
                eq("SKILL_VERSION"), eq(31L), eq("req-1"), eq("10.0.0.1"), eq("junit"), contains("sha-main"));
        Mockito.verify(scanner).walk(any(), eq("acme"), eq("skills"), eq("sha-main"), eq("skills/foo"));
    }

    @Test
    void tagRefNamesTheVersionInsteadOfTheFrontmatter() {
        stubSkillAtPath("skills/foo", "0.9.0");
        Mockito.when(publish.publish(anyString(), anyList(), anyString(), any(), anySet(), anyBoolean(), any(),
                any(), any(), any(), any(), any())).thenReturn(publishedResult);

        service.importSkill("team", TAG_URL, null, "skills/foo", SkillVisibility.PUBLIC, false, null, null,
                null, "user-1", Set.of(), CONTEXT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PackageEntry>> entries = ArgumentCaptor.forClass(List.class);
        Mockito.verify(publish).publish(eq("team"), entries.capture(), eq("user-1"),
                eq(SkillVisibility.PUBLIC), eq(Set.of()), eq(false),
                eq(SkillPublishAppService.AvatarAction.KEEP), isNull(), isNull(), isNull(), isNull(),
                eq(CONTEXT));
        String skillMd = new String(entries.getValue().get(0).content(), StandardCharsets.UTF_8);
        assertThat(skillMd).contains("version: v1.2.3").doesNotContain("0.9.0");
    }

    @Test
    void refusesToImportWhenNeitherTagNorFrontmatterNamesAVersion() {
        stubSkillAtPath("skills/foo", null);

        assertThatThrownBy(() -> service.importSkill("team", BRANCH_URL, null, "skills/foo",
                SkillVisibility.PUBLIC, false, null, null, null, "user-1", Set.of(), CONTEXT))
                .isInstanceOf(DomainBadRequestException.class)
                .satisfies(exception -> {
                    DomainBadRequestException badRequest = (DomainBadRequestException) exception;
                    assertThat(badRequest.messageCode()).isEqualTo(GitcodeImportAppService.MISSING_VERSION_CODE);
                    assertThat(badRequest.messageArgs()).containsExactly("version");
                });
        Mockito.verify(publish, Mockito.never()).publish(anyString(), anyList(), anyString(), any(), anySet(),
                anyBoolean(), any(), any(), any(), any(), any(), any());
        Mockito.verify(bindings, Mockito.never()).save(any());
    }

    @Test
    void refusesToGuessWhichOfSeveralSkillDirectoriesToImport() {
        GitcodeSkillScanner.Tree tree = new GitcodeSkillScanner.Tree("", List.of("a/SKILL.md", "b/SKILL.md"),
                List.of("a", "b"));
        Mockito.when(scanner.walk(any(), anyString(), anyString(), anyString(), isNull())).thenReturn(tree);

        assertThatThrownBy(() -> service.importSkill("team", ROOT_URL, null, null, SkillVisibility.PUBLIC,
                false, null, null, null, "user-1", Set.of(), CONTEXT))
                .isInstanceOf(DomainBadRequestException.class)
                .satisfies(exception -> assertThat(((DomainBadRequestException) exception).messageCode())
                        .isEqualTo(GitcodeErrors.SKILL_AMBIGUOUS));
        Mockito.verify(publish, Mockito.never()).publish(anyString(), anyList(), anyString(), any(), anySet(),
                anyBoolean(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void uploadsTheChosenDirectoryWhenTheRequestNamesOne() {
        GitcodeSkillScanner.Tree tree = new GitcodeSkillScanner.Tree("", List.of("a/SKILL.md", "b/SKILL.md"),
                List.of("a", "b"));
        GitcodeSkillScanner.ScannedSkill chosen = scannedSkill("b", "2.0.0");
        Mockito.when(scanner.walk(any(), anyString(), anyString(), anyString(), eq("b"))).thenReturn(tree);
        Mockito.when(scanner.load(any(), anyString(), anyString(), anyString(), eq(tree), eq("b")))
                .thenReturn(chosen);
        Mockito.when(publish.publish(anyString(), anyList(), anyString(), any(), anySet(), anyBoolean(), any(),
                any(), any(), any(), any(), any())).thenReturn(publishedResult);

        service.importSkill("team", ROOT_URL, "main", "b", SkillVisibility.NAMESPACE_ONLY, false, null, null,
                null, "user-1", Set.of(), CONTEXT);

        ArgumentCaptor<GitcodeSourceBinding> binding = ArgumentCaptor.forClass(GitcodeSourceBinding.class);
        Mockito.verify(bindings).save(binding.capture());
        assertThat(binding.getValue().getSkillPath()).isEqualTo("b");
    }

    @Test
    void leavesNoBindingBehindWhenPublishingFails() {
        stubSkillAtPath("skills/foo", "1.0.0");
        Mockito.when(publish.publish(anyString(), anyList(), anyString(), any(), anySet(), anyBoolean(), any(),
                any(), any(), any(), any(), any()))
                .thenThrow(new DomainBadRequestException("error.skill.publish.package.invalid"));

        assertThatThrownBy(() -> service.importSkill("team", BRANCH_URL, null, "skills/foo",
                SkillVisibility.PUBLIC, false, null, null, null, "user-1", Set.of(), CONTEXT))
                .isInstanceOf(DomainBadRequestException.class);
        Mockito.verify(bindings, Mockito.never()).save(any());
        Mockito.verify(audit, Mockito.never()).record(anyString(), anyString(), anyString(), any(),
                any(), any(), any(), any());
    }

    @Test
    void reimportingTheSameCommitReturnsTheVersionAlreadyPublished() {
        GitcodeSourceBinding existing = existingBinding("sha-main", SkillVisibility.PUBLIC, 31L);
        Mockito.when(bindings.findByNamespaceIdAndOwnerLoginAndRepositoryNameAndSkillPath(
                7L, "acme", "skills", "skills/foo")).thenReturn(Optional.of(existing));
        Mockito.when(versions.findById(31L)).thenReturn(Optional.of(publishedVersion));
        Skill skill = Mockito.mock(Skill.class);
        Mockito.when(skill.getSlug()).thenReturn("demo-skill");
        Mockito.when(skills.findById(41L)).thenReturn(Optional.of(skill));
        GitcodeSkillScanner.Tree tree = new GitcodeSkillScanner.Tree("skills/foo",
                List.of("skills/foo/SKILL.md"), List.of("skills/foo"));
        Mockito.when(scanner.walk(any(), anyString(), anyString(), anyString(), eq("skills/foo"))).thenReturn(tree);

        PublishResponse response = service.importSkill("team", BRANCH_URL, null, "skills/foo",
                SkillVisibility.PUBLIC, false, null, null, null, "user-1", Set.of(), CONTEXT);

        assertThat(response.skillId()).isEqualTo(41L);
        assertThat(response.slug()).isEqualTo("demo-skill");
        assertThat(response.version()).isEqualTo("1.0.0");
        assertThat(response.status()).isEqualTo("PUBLISHED");
        Mockito.verify(publish, Mockito.never()).publish(anyString(), anyList(), anyString(), any(), anySet(),
                anyBoolean(), any(), any(), any(), any(), any(), any());
        Mockito.verify(scanner, Mockito.never()).load(any(), anyString(), anyString(), anyString(), any(),
                anyString());
        Mockito.verify(bindings).save(existing);
        assertThat(existing.getLastImportedAt()).isEqualTo(NOW);
    }

    @Test
    void reimportingANewerCommitPublishesAndMovesTheBinding() {
        GitcodeSourceBinding existing = existingBinding("old-sha", SkillVisibility.PUBLIC, 30L);
        Mockito.when(bindings.findByNamespaceIdAndOwnerLoginAndRepositoryNameAndSkillPath(
                7L, "acme", "skills", "skills/foo")).thenReturn(Optional.of(existing));
        stubSkillAtPath("skills/foo", "1.0.0");
        Mockito.when(publish.publish(anyString(), anyList(), anyString(), any(), anySet(), anyBoolean(), any(),
                any(), any(), any(), any(), any())).thenReturn(publishedResult);

        service.importSkill("team", BRANCH_URL, null, "skills/foo", SkillVisibility.PUBLIC, false, null, null,
                null, "user-1", Set.of(), CONTEXT);

        Mockito.verify(publish, Mockito.times(1)).publish(eq("team"), anyList(), eq("user-1"),
                eq(SkillVisibility.PUBLIC), eq(Set.of()), eq(false),
                eq(SkillPublishAppService.AvatarAction.KEEP), isNull(), isNull(), isNull(), isNull(),
                eq(CONTEXT));
        Mockito.verify(bindings).save(existing);
        assertThat(existing.getCommitSha()).isEqualTo("sha-main");
        assertThat(existing.getSkillVersionId()).isEqualTo(31L);
    }

    @Test
    void acceptsAnExplicitCommitRef() {
        stubSkillAtPath("skills/foo", "1.0.0");
        Mockito.when(publish.publish(anyString(), anyList(), anyString(), any(), anySet(), anyBoolean(), any(),
                any(), any(), any(), any(), any())).thenReturn(publishedResult);
        String commit = "1b8946cd51686b232a31614f3ba7d853d6d0b3d4";

        service.importSkill("team", ROOT_URL, commit, "skills/foo", SkillVisibility.PUBLIC, false, null, null,
                null, "user-1", Set.of(), CONTEXT);

        ArgumentCaptor<GitcodeSourceBinding> binding = ArgumentCaptor.forClass(GitcodeSourceBinding.class);
        Mockito.verify(bindings).save(binding.capture());
        assertThat(binding.getValue().getRefType()).isEqualTo(GitcodeRefType.COMMIT);
        assertThat(binding.getValue().getCommitSha()).isEqualTo(commit);
        Mockito.verify(scanner).walk(any(), eq("acme"), eq("skills"), eq(commit), eq("skills/foo"));
    }

    @Test
    void rejectsARefThatIsNeitherBranchTagNorCommit() {
        assertThatThrownBy(() -> service.importSkill("team", ROOT_URL, "release-candidate", "skills/foo",
                SkillVisibility.PUBLIC, false, null, null, null, "user-1", Set.of(), CONTEXT))
                .isInstanceOf(DomainBadRequestException.class)
                .satisfies(exception -> assertThat(((DomainBadRequestException) exception).messageCode())
                        .isEqualTo(GitcodeErrors.URL_INVALID));
    }

    @Test
    void inspectReportsTheResolvedRevisionAndEverySkillFound() {
        GitcodeSkillScanner.Tree tree = new GitcodeSkillScanner.Tree("", List.of(), List.of("a", "b"));
        Mockito.when(scanner.discover(any(), eq("acme"), eq("skills"), eq("sha-main"), any()))
                .thenReturn(new GitcodeSkillScanner.Discovery(tree, List.of(
                        new GitcodeSkillScanner.DiscoveredSkill("a", "a", "desc a", "1.0.0", 2),
                        new GitcodeSkillScanner.DiscoveredSkill("b", "b", "desc b", null, 1))));

        GitcodeInspectResponse response = service.inspect(ROOT_URL, null, null);

        assertThat(response.owner()).isEqualTo("acme");
        assertThat(response.repository()).isEqualTo("skills");
        assertThat(response.defaultBranch()).isEqualTo("main");
        assertThat(response.ref()).isEqualTo("main");
        assertThat(response.refType()).isEqualTo(GitcodeRefType.BRANCH);
        assertThat(response.commitSha()).isEqualTo("sha-main");
        assertThat(response.skills()).extracting(GitcodeInspectResponse.Skill::path)
                .containsExactly("a", "b");
        assertThat(response.skills()).extracting(GitcodeInspectResponse.Skill::version)
                .containsExactly("1.0.0", null);
    }

    @Test
    void replacesTopLevelVersionLineInSkillMd() {
        String content = "---\nname: a\ndescription: b\nversion: 0.9.0\n---\n# body\n";

        assertThat(GitcodeImportAppService.withFrontmatterVersion(content, "v1.2.3"))
                .isEqualTo("---\nname: a\ndescription: b\nversion: v1.2.3\n---\n# body\n");
    }

    @Test
    void insertsVersionWhenTheFrontmatterHasNone() {
        String content = "---\nname: a\ndescription: b\n---\n# body\n";

        assertThat(GitcodeImportAppService.withFrontmatterVersion(content, "v1.2.3"))
                .isEqualTo("---\nname: a\ndescription: b\nversion: v1.2.3\n---\n# body\n");
    }

    @Test
    void keepsANestedMetadataVersionWhileAddingTheTopLevelOne() {
        String content = "---\nname: a\ndescription: b\nmetadata:\n  version: 0.0.1\n---\n# body\n";

        String updated = GitcodeImportAppService.withFrontmatterVersion(content, "v2.0.0");

        assertThat(updated).contains("  version: 0.0.1").contains("\nversion: v2.0.0\n");
    }

    private void stubSkillAtPath(String skillPath, String version) {
        GitcodeSkillScanner.Tree tree = new GitcodeSkillScanner.Tree(skillPath,
                List.of(skillPath.isEmpty() ? "SKILL.md" : skillPath + "/SKILL.md"), List.of(skillPath));
        Mockito.when(scanner.walk(any(), anyString(), anyString(), anyString(), eq(skillPath))).thenReturn(tree);
        Mockito.when(scanner.load(any(), anyString(), anyString(), anyString(), eq(tree), eq(skillPath)))
                .thenReturn(scannedSkill(skillPath, version));
    }

    private static GitcodeSkillScanner.ScannedSkill scannedSkill(String path, String version) {
        byte[] content = skillMarkdown(version);
        PackageEntry entry = new PackageEntry("SKILL.md", content, content.length, "text/markdown");
        return new GitcodeSkillScanner.ScannedSkill(path, "demo-skill", "A demo skill", version, 1,
                content.length, List.of(entry));
    }

    private static byte[] skillMarkdown(String version) {
        String versionLine = version == null ? "" : "version: " + version + "\n";
        return ("---\nname: demo-skill\ndescription: A demo skill\n" + versionLine + "---\n# Demo\n")
                .getBytes(StandardCharsets.UTF_8);
    }



    private static GitcodeSourceBinding existingBinding(String commitSha, SkillVisibility visibility,
                                                       long skillVersionId) {
        GitcodeSourceBinding binding = new GitcodeSourceBinding(7L, "acme", "skills", "skills/foo", "main",
                GitcodeRefType.BRANCH, commitSha, GitcodeLinkType.ONE_SHOT, visibility, "user-1",
                Instant.parse("2026-11-01T00:00:00Z"));
        binding.recordImport("main", GitcodeRefType.BRANCH, commitSha, visibility, 41L, skillVersionId,
                Instant.parse("2026-11-01T00:00:00Z"));
        return binding;
    }
}
