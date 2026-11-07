package com.iflytek.skillhub.service;

import com.iflytek.skillhub.config.SkillPublishProperties;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.dto.GitCodeImportPublishRequest;
import com.iflytek.skillhub.dto.GitCodeImportPublishResponse;
import com.iflytek.skillhub.dto.GitCodeImportResolveRequest;
import com.iflytek.skillhub.dto.GitCodeImportResolveResponse;
import com.iflytek.skillhub.gitcode.FakeGitCodeApiClient;
import com.iflytek.skillhub.gitcode.GitCodeImportProperties;
import com.iflytek.skillhub.gitcode.GitCodeRepoUrl;
import com.iflytek.skillhub.gitcode.GitCodeSnapshotMaterializer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the core import invariant: the commit resolved for a preview is the commit that is
 * published, even when the repository's default branch has moved in between.
 */
class GitCodeImportAppServiceTest {

    private static final String FIRST_COMMIT = "1111111111111111111111111111111111111111";
    private static final String SECOND_COMMIT = "2222222222222222222222222222222222222222";
    private static final String REPO_URL = "https://gitcode.com/openharmony/skills";
    private static final String OWNER_REPO = "openharmony/skills";

    private final FakeGitCodeApiClient client = new FakeGitCodeApiClient();
    private final GitCodeSnapshotMaterializer materializer = new GitCodeSnapshotMaterializer(
            client, new GitCodeImportProperties(), new SkillPublishProperties());
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final GitCodeImportAppService service = new GitCodeImportAppService(materializer, publisher, null);

    @Test
    void resolvePinsHeadCommitOfDefaultBranchAndListsEverySkill() {
        client.head(OWNER_REPO, FIRST_COMMIT, "master")
                .commit(FIRST_COMMIT, Map.of(
                        "skills/alpha/SKILL.md", "alpha",
                        "skills/beta/SKILL.md", "beta"));

        GitCodeImportResolveResponse response =
                service.resolve(new GitCodeImportResolveRequest(REPO_URL, null, null));

        assertThat(response.commit()).isEqualTo(FIRST_COMMIT);
        assertThat(response.defaultBranch()).isEqualTo("master");
        assertThat(response.repoUrl()).isEqualTo(REPO_URL);
        assertThat(response.candidates()).extracting(GitCodeImportResolveResponse.Candidate::path)
                .containsExactly("skills/alpha", "skills/beta");
        assertThat(response.selected()).isNull();
    }

    @Test
    void resolveOfOnePathReturnsFileManifestAndDigest() {
        client.head(OWNER_REPO, FIRST_COMMIT, "master")
                .commit(FIRST_COMMIT, Map.of(
                        "skills/alpha/SKILL.md", "alpha",
                        "skills/alpha/reference/guide.md", "guide"));

        GitCodeImportResolveResponse response =
                service.resolve(new GitCodeImportResolveRequest(REPO_URL, null, "skills/alpha"));

        assertThat(response.selected()).isNotNull();
        assertThat(response.selected().contentDigest()).matches("[0-9a-f]{64}");
        assertThat(response.selected().files()).extracting(GitCodeImportResolveResponse.File::path)
                .containsExactly("SKILL.md", "reference/guide.md");
    }

    @Test
    void publishKeepsPreviewedCommitAfterTheBranchMoves() {
        Map<String, String> previewedFiles = new LinkedHashMap<>();
        previewedFiles.put("skills/alpha/SKILL.md", "previewed content");
        client.head(OWNER_REPO, FIRST_COMMIT, "master").commit(FIRST_COMMIT, previewedFiles);

        GitCodeImportResolveResponse preview =
                service.resolve(new GitCodeImportResolveRequest(REPO_URL, null, "skills/alpha"));

        // The default branch advances after the preview.
        client.commit(SECOND_COMMIT, Map.of("skills/alpha/SKILL.md", "newer content"))
                .advanceHead(OWNER_REPO, SECOND_COMMIT);

        GitCodeImportPublishResponse published = service.publish(
                "user-1", Set.of("USER"),
                new GitCodeImportPublishRequest(REPO_URL, preview.commit(), "master", "skills/alpha",
                        "ns", "PUBLIC", null, null, preview.selected().contentDigest()),
                null);

        assertThat(published.commit()).isEqualTo(FIRST_COMMIT);
        assertThat(published.contentDigest()).isEqualTo(preview.selected().contentDigest());
        assertThat(publisher.recorded.commit()).isEqualTo(FIRST_COMMIT);
        assertThat(publisher.recorded.digest()).isEqualTo(preview.selected().contentDigest());
        assertThat(new String(publisher.recordedFiles.get("SKILL.md"), StandardCharsets.UTF_8))
                .isEqualTo("previewed content");
    }

    @Test
    void publishRejectsContentThatNoLongerMatchesThePreview() {
        client.head(OWNER_REPO, FIRST_COMMIT, "master")
                .commit(FIRST_COMMIT, Map.of("skills/alpha/SKILL.md", "previewed content"));
        GitCodeImportResolveResponse preview =
                service.resolve(new GitCodeImportResolveRequest(REPO_URL, null, "skills/alpha"));

        assertThatThrownBy(() -> service.publish("user-1", Set.of("USER"),
                new GitCodeImportPublishRequest(REPO_URL, preview.commit(), "master", "skills/alpha",
                        "ns", "PUBLIC", null, null, "f".repeat(64)), null))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.snapshot.changed"));
        assertThat(publisher.recorded).isNull();
    }

    @Test
    void publishFailsExplicitlyWhenPinnedSnapshotIsGoneAndWritesNothing() {
        client.head(OWNER_REPO, FIRST_COMMIT, "master")
                .commit(FIRST_COMMIT, Map.of("skills/alpha/SKILL.md", "previewed content"));
        GitCodeImportResolveResponse preview =
                service.resolve(new GitCodeImportResolveRequest(REPO_URL, null, "skills/alpha"));

        client.dropCommit(FIRST_COMMIT);

        assertThatThrownBy(() -> service.publish("user-1", Set.of("USER"),
                new GitCodeImportPublishRequest(REPO_URL, preview.commit(), "master", "skills/alpha",
                        "ns", "PUBLIC", null, null, preview.selected().contentDigest()), null))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.snapshot.unavailable"));
        assertThat(publisher.recorded).isNull();
    }

    @Test
    void publishRejectsUnsupportedSourceAndMalformedCommitWithoutWriting() {
        client.head(OWNER_REPO, FIRST_COMMIT, "master")
                .commit(FIRST_COMMIT, Map.of("skills/alpha/SKILL.md", "content"));

        assertThatThrownBy(() -> service.publish("user-1", Set.of("USER"),
                new GitCodeImportPublishRequest("https://github.com/openharmony/skills", FIRST_COMMIT,
                        "master", "skills/alpha", "ns", "PUBLIC", null, null, "a".repeat(64)), null))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.source.unsupported"));

        assertThatThrownBy(() -> service.publish("user-1", Set.of("USER"),
                new GitCodeImportPublishRequest(REPO_URL, "abc", "master", "skills/alpha",
                        "ns", "PUBLIC", null, null, "a".repeat(64)), null))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.commit.invalid"));

        assertThat(publisher.recorded).isNull();
    }

    @Test
    void resolveRejectsRepoWithoutUsableSkillAndUnsafePath() {
        client.head(OWNER_REPO, FIRST_COMMIT, "master")
                .commit(FIRST_COMMIT, Map.of("docs/notes.md", "not a skill"));

        assertThatThrownBy(() -> service.resolve(new GitCodeImportResolveRequest(REPO_URL, null, null)))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.skill.notFound"));

        assertThatThrownBy(() -> service.resolve(
                new GitCodeImportResolveRequest(REPO_URL, FIRST_COMMIT, "../../etc")))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.path.unsafe"));
    }

    /** Captures the snapshot handed to publication instead of touching the database. */
    private static final class RecordingPublisher extends GitCodeImportPublisher {

        private GitCodeSnapshotMaterializer.Snapshot recorded;
        private Map<String, byte[]> recordedFiles = Map.of();

        private RecordingPublisher() {
            super(null, null, null, null);
        }

        @Override
        public GitCodeImportPublishResponse publish(GitCodeRepoUrl repo, String branch, String commit,
                                                     GitCodeSnapshotMaterializer.Snapshot snapshot,
                                                     String namespace, SkillVisibility visibility,
                                                     String displayName, String changelog, String actor,
                                                     Set<String> roles, AuditRequestContext context) {
            this.recorded = snapshot;
            this.recordedFiles = snapshot.files().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            GitCodeSnapshotMaterializer.FileBlob::path,
                            GitCodeSnapshotMaterializer.FileBlob::content));
            return new GitCodeImportPublishResponse(1L, 11L, namespace, "alpha", "1.0.0", "PUBLISHED",
                    snapshot.fileCount(), snapshot.totalSize(), repo.canonicalUrl(), snapshot.path(),
                    branch, commit, snapshot.digest());
        }
    }
}


