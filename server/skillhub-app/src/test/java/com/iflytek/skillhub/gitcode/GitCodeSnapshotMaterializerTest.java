package com.iflytek.skillhub.gitcode;

import com.iflytek.skillhub.config.SkillPublishProperties;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitCodeSnapshotMaterializerTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String BINARY_COMMIT = "beefbeefbeefbeefbeefbeefbeefbeefbeefbeef";
    private static final GitCodeRepoUrl REPO = new GitCodeRepoUrl("openharmony", "skills");

    private final FakeGitCodeApiClient client = new FakeGitCodeApiClient();
    private final GitCodeImportProperties properties = new GitCodeImportProperties();
    private final GitCodeSnapshotMaterializer materializer =
            new GitCodeSnapshotMaterializer(client, properties, new SkillPublishProperties());

    @Test
    void discoversEverySkillRootInAMultiSkillRepository() {
        client.head(REPO.ownerRepoKey(), COMMIT, "master")
                .commit(COMMIT, Map.of(
                        "README.md", "# repository",
                        "skills/alpha/SKILL.md", "alpha",
                        "skills/alpha/reference/guide.md", "guide",
                        "skills/beta/SKILL.md", "beta",
                        "docs/notes.md", "not a skill"));

        List<GitCodeSnapshotMaterializer.Candidate> candidates = materializer.discover(REPO, COMMIT);

        assertThat(candidates).extracting(GitCodeSnapshotMaterializer.Candidate::path)
                .containsExactly("skills/alpha", "skills/beta");
    }

    @Test
    void discoveryDoesNotReadFileContentOrDescendIntoASkillRoot() {
        client.commit(COMMIT, Map.of(
                "skills/alpha/SKILL.md", "alpha",
                "skills/alpha/nested/other/SKILL.md", "nested skill inside a skill"));

        List<GitCodeSnapshotMaterializer.Candidate> candidates = materializer.discover(REPO, COMMIT);

        // A nested SKILL.md belongs to the outer skill's content, not to a second candidate.
        assertThat(candidates).extracting(GitCodeSnapshotMaterializer.Candidate::path)
                .containsExactly("skills/alpha");
        assertThat(client.contentReads()).isZero();
    }

    @Test
    void materializeReRootsFilesAtTheSelectedSkillAndHashesContent() {
        client.commit(COMMIT, Map.of(
                "skills/alpha/SKILL.md", "---\nname: alpha\n---\nbody",
                "skills/alpha/reference/guide.md", "guide"));

        GitCodeSnapshotMaterializer.Snapshot snapshot = materializer.materialize(REPO, COMMIT, "skills/alpha");

        assertThat(snapshot.path()).isEqualTo("skills/alpha");
        assertThat(snapshot.commit()).isEqualTo(COMMIT);
        assertThat(snapshot.files()).extracting(GitCodeSnapshotMaterializer.FileBlob::path)
                .containsExactly("SKILL.md", "reference/guide.md");
        assertThat(snapshot.fileCount()).isEqualTo(2);
        assertThat(snapshot.digest()).matches("[0-9a-f]{64}");
        assertThat(snapshot.manifestJson()).contains("\"path\":\"SKILL.md\"");
        assertThat(snapshot.files().getFirst().sha256()).isEqualTo(GitCodeSnapshotMaterializer.sha256Hex(
                "---\nname: alpha\n---\nbody".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void digestIsStableForTheSameFileSet() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "alpha");
        files.put("a/b.md", "b");
        client.commit(COMMIT, files);

        GitCodeSnapshotMaterializer.Snapshot first = materializer.materialize(REPO, COMMIT, "");
        assertThat(first.digest()).isEqualTo(materializer.materialize(REPO, COMMIT, "").digest());
    }

    @Test
    void materializesFilesWhoseProviderSizeIsUnknown() {
        // GitCode directory listings omit "size"; the materializer must use real bytes instead of
        // treating the missing size as an empty or truncated file.
        FakeGitCodeApiClient unsized = new FakeGitCodeApiClient() {
            @Override
            public List<DirectoryEntry> list(GitCodeRepoUrl repo, String path, String ref) {
                return super.list(repo, path, ref).stream()
                        .map(entry -> new DirectoryEntry(entry.type(), entry.path(), -1L))
                        .toList();
            }
        };
        unsized.commit(COMMIT, Map.of("skill/SKILL.md", "0123456789", "skill/notes.md", "abc"));
        GitCodeSnapshotMaterializer loose = new GitCodeSnapshotMaterializer(
                unsized, properties, new SkillPublishProperties());

        GitCodeSnapshotMaterializer.Snapshot snapshot = loose.materialize(REPO, COMMIT, "skill");

        assertThat(snapshot.fileCount()).isEqualTo(2);
        assertThat(snapshot.totalSize()).isEqualTo(13);
        assertThat(snapshot.files()).extracting(GitCodeSnapshotMaterializer.FileBlob::size)
                .containsExactly(3L, 10L);
    }

    @Test
    void rejectsUnsafePaths() {
        for (String path : new String[]{
                "../etc/passwd",
                "/etc/passwd",
                "skills/../../etc",
                "skills//alpha",
                "C:\\skills\\alpha",
                "~/skills",
                "skills/./alpha"}) {
            assertThatThrownBy(() -> materializer.normalizePath(path))
                    .as(path)
                    .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                            assertThat(exception.messageCode()).isEqualTo("error.skill.import.path.unsafe"));
        }
    }

    @Test
    void rejectsPathWithoutSkillMd() {
        client.commit(COMMIT, Map.of("skills/alpha/reference.md", "x"));

        assertThatThrownBy(() -> materializer.materialize(REPO, COMMIT, "skills/alpha"))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.skill.notFound"));
    }

    @Test
    void rejectsBinaryContentBehindATextExtension() {
        client.commitBytes(BINARY_COMMIT, Map.of(
                "skill/SKILL.md", "skill".getBytes(StandardCharsets.UTF_8),
                "skill/blob.md", new byte[]{0x00, 0x01, 0x02, 0x00}));

        assertThatThrownBy(() -> materializer.materialize(REPO, BINARY_COMMIT, "skill"))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.file.invalid"));
    }

    @Test
    void rejectsExtensionlessBinaryFiles() {
        client.commitBytes(BINARY_COMMIT, Map.of(
                "skill/SKILL.md", "skill".getBytes(StandardCharsets.UTF_8),
                "skill/LICENSE", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x00, 0x00}));

        assertThatThrownBy(() -> materializer.materialize(REPO, BINARY_COMMIT, "skill"))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.file.binary"));
    }

    @Test
    void rejectsFileTypesOutsideThePackageAllowlist() {
        client.commit(COMMIT, Map.of(
                "skill/SKILL.md", "skill",
                "skill/tool.exe", "MZ binary"));

        assertThatThrownBy(() -> materializer.materialize(REPO, COMMIT, "skill"))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.file.unsupported"));
    }

    @Test
    void acceptsExtensionlessPlainTextFiles() {
        client.commit(COMMIT, Map.of(
                "skill/SKILL.md", "skill",
                "skill/LICENSE", "Apache License"));

        GitCodeSnapshotMaterializer.Snapshot snapshot = materializer.materialize(REPO, COMMIT, "skill");

        assertThat(snapshot.files()).extracting(GitCodeSnapshotMaterializer.FileBlob::path)
                .containsExactly("LICENSE", "SKILL.md");
    }

    @Test
    void reportsUnavailableSnapshotWhenPinnedCommitCannotBeRead() {
        client.commit(COMMIT, Map.of("alpha/SKILL.md", "alpha")).dropCommit(COMMIT);

        assertThatThrownBy(() -> materializer.materialize(REPO, COMMIT, "alpha"))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.snapshot.unavailable"));
    }

    @Test
    void reportsMissingRepositoryAsNotFoundAndUnreachableSourceSeparately() {
        client.failWith(REPO.ownerRepoKey(), GitCodeApiException.Kind.NOT_FOUND);
        assertThatThrownBy(() -> materializer.head(REPO))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.source.notFound"));

        client.failWith(REPO.ownerRepoKey(), GitCodeApiException.Kind.FORBIDDEN);
        assertThatThrownBy(() -> materializer.head(REPO))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.source.notPublic"));

        client.failWith(REPO.ownerRepoKey(), GitCodeApiException.Kind.UNAVAILABLE);
        assertThatThrownBy(() -> materializer.head(REPO))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.source.unreachable"));
    }

    @Test
    void reportsRateLimitedSourceAsItsOwnFailure() {
        client.failWith(REPO.ownerRepoKey(), GitCodeApiException.Kind.RATE_LIMITED);

        assertThatThrownBy(() -> materializer.head(REPO))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.source.busy"));
    }

    @Test
    void rejectsSubmoduleEntriesInsteadOfPublishingPartialContent() {
        FakeGitCodeApiClient submoduleClient = new FakeGitCodeApiClient() {
            @Override
            public List<DirectoryEntry> list(GitCodeRepoUrl repo, String path, String ref) {
                if (path.isEmpty()) {
                    return List.of(new DirectoryEntry("tree", "skill", 0));
                }
                return List.of(
                        new DirectoryEntry("file", "skill/SKILL.md", 5),
                        new DirectoryEntry("submodule", "skill/vendor", 0));
            }
        };
        GitCodeSnapshotMaterializer strict = new GitCodeSnapshotMaterializer(
                submoduleClient, properties, new SkillPublishProperties());

        assertThatThrownBy(() -> strict.materialize(REPO, COMMIT, "skill"))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.file.invalid"));
    }

    @Test
    void rejectsSymlinkEntriesWhichCouldPointOutsideTheSkillRoot() {
        FakeGitCodeApiClient symlinkClient = new FakeGitCodeApiClient() {
            @Override
            public List<DirectoryEntry> list(GitCodeRepoUrl repo, String path, String ref) {
                if (path.isEmpty()) {
                    return List.of(new DirectoryEntry("tree", "skill", 0));
                }
                return List.of(
                        new DirectoryEntry("file", "skill/SKILL.md", 5),
                        new DirectoryEntry("symlink", "skill/etc", 0));
            }
        };
        GitCodeSnapshotMaterializer strict = new GitCodeSnapshotMaterializer(
                symlinkClient, properties, new SkillPublishProperties());

        assertThatThrownBy(() -> strict.materialize(REPO, COMMIT, "skill"))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.file.invalid"));
    }

    @Test
    void rejectsProviderEntryPathsThatAreNotNormalizedRelativePaths() {
        FakeGitCodeApiClient traversalClient = new FakeGitCodeApiClient() {
            @Override
            public List<DirectoryEntry> list(GitCodeRepoUrl repo, String path, String ref) {
                if (path.isEmpty()) {
                    return List.of(new DirectoryEntry("tree", "skill", 0));
                }
                return List.of(
                        new DirectoryEntry("file", "skill/SKILL.md", 5),
                        new DirectoryEntry("file", "skill/../../../etc/passwd", 5));
            }
        };
        GitCodeSnapshotMaterializer strict = new GitCodeSnapshotMaterializer(
                traversalClient, properties, new SkillPublishProperties());

        assertThatThrownBy(() -> strict.materialize(REPO, COMMIT, "skill"))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.path.unsafe"));
    }

    @Test
    void rejectsSnapshotsAboveThePublishFileLimit() {
        SkillPublishProperties limited = new SkillPublishProperties();
        limited.setMaxFileCount(1);
        GitCodeSnapshotMaterializer bounded = new GitCodeSnapshotMaterializer(client, properties, limited);
        client.commit(COMMIT, Map.of("skill/SKILL.md", "a", "skill/two.md", "b"));

        assertThatThrownBy(() -> bounded.materialize(REPO, COMMIT, "skill"))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.limit.exceeded"));
    }

    @Test
    void rejectsSnapshotsAboveTheSingleFileLimit() {
        SkillPublishProperties limited = new SkillPublishProperties();
        limited.setMaxSingleFileSize(2);
        GitCodeSnapshotMaterializer bounded = new GitCodeSnapshotMaterializer(client, properties, limited);
        client.commit(COMMIT, Map.of("skill/SKILL.md", "too large"));

        assertThatThrownBy(() -> bounded.materialize(REPO, COMMIT, "skill"))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.limit.exceeded"));
    }

    @Test
    void rejectsRepositoriesWithTooManyDirectories() {
        GitCodeImportProperties shallow = new GitCodeImportProperties();
        shallow.setMaxDirectories(1);
        GitCodeSnapshotMaterializer bounded = new GitCodeSnapshotMaterializer(
                client, shallow, new SkillPublishProperties());
        client.commit(COMMIT, Map.of("a/SKILL.md", "a", "b/SKILL.md", "b"));

        assertThatThrownBy(() -> bounded.discover(REPO, COMMIT))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.limit.exceeded"));
    }
}
