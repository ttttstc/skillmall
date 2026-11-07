package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.SkillImportSource;
import com.iflytek.skillhub.domain.skill.SkillImportSourceRepository;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.dto.GitCodeImportPublishRequest;
import com.iflytek.skillhub.dto.GitCodeImportPublishResponse;
import com.iflytek.skillhub.dto.GitCodeImportResolveRequest;
import com.iflytek.skillhub.dto.GitCodeImportResolveResponse;
import com.iflytek.skillhub.gitcode.GitCodeApiClient;
import com.iflytek.skillhub.gitcode.GitCodeRepoUrl;
import com.iflytek.skillhub.gitcode.GitCodeSnapshotMaterializer;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Imports skills from a pinned snapshot of a public GitCode repository.
 *
 * <p>The invariant is that a preview and its publication observe the same commit: resolving the
 * default branch happens only when the caller has not supplied a commit, and publication always
 * re-reads the supplied commit instead of re-resolving the branch. A branch that moves between the
 * two calls therefore cannot change what gets published; if the pinned snapshot can no longer be
 * read the import fails explicitly instead of falling back to newer content.
 */
@Service
public class GitCodeImportAppService {

    private static final String PROVIDER = "GITCODE";

    private final GitCodeSnapshotMaterializer materializer;
    private final GitCodeImportPublisher publisher;
    private final SkillImportSourceRepository sources;

    public GitCodeImportAppService(GitCodeSnapshotMaterializer materializer, GitCodeImportPublisher publisher,
                                   SkillImportSourceRepository sources) {
        this.materializer = materializer;
        this.publisher = publisher;
        this.sources = sources;
    }

    /** Resolves the pinned commit, the available skill roots, and optionally one materialized skill. */
    public GitCodeImportResolveResponse resolve(GitCodeImportResolveRequest request) {
        if (request == null) {
            throw new DomainBadRequestException("error.skill.import.url.invalid");
        }
        GitCodeRepoUrl repo = GitCodeRepoUrl.parse(request.repoUrl());
        String commit = commitOrNull(request.commit());
        String defaultBranch = null;
        if (commit == null) {
            GitCodeApiClient.RepositoryHead head = materializer.head(repo);
            commit = head.commit();
            defaultBranch = head.defaultBranch();
        }

        boolean selected = request.path() != null && !request.path().isBlank();
        if (!selected) {
            List<GitCodeImportResolveResponse.Candidate> candidates = materializer.discover(repo, commit).stream()
                    .map(candidate -> new GitCodeImportResolveResponse.Candidate(candidate.path()))
                    .toList();
            if (candidates.isEmpty()) {
                throw new DomainBadRequestException("error.skill.import.skill.notFound", repo.canonicalUrl());
            }
            return new GitCodeImportResolveResponse(PROVIDER, repo.canonicalUrl(), repo.owner(), repo.repo(),
                    defaultBranch, commit, candidates, null);
        }

        GitCodeSnapshotMaterializer.Snapshot snapshot =
                materializer.materialize(repo, commit, request.path());
        return new GitCodeImportResolveResponse(PROVIDER, repo.canonicalUrl(), repo.owner(), repo.repo(),
                defaultBranch, commit,
                List.of(new GitCodeImportResolveResponse.Candidate(snapshot.path())),
                toSelected(snapshot));
    }

    /** Provenance of one published version, for traceability outside the publishing call. */
    public Optional<SkillImportSource> source(Long skillVersionId) {
        if (skillVersionId == null || skillVersionId <= 0) {
            throw new DomainNotFoundException("error.notFound");
        }
        return sources.findBySkillVersionId(skillVersionId);
    }

    /**
     * Publishes the previewed snapshot. Re-reading the pinned commit (never the branch) keeps the
     * previewed content authoritative even when the repository has moved on.
     */
    public GitCodeImportPublishResponse publish(String actor, Set<String> roles,
                                                GitCodeImportPublishRequest request,
                                                AuditRequestContext context) {
        if (request == null) {
            throw new DomainBadRequestException("error.skill.import.url.invalid");
        }
        GitCodeRepoUrl repo = GitCodeRepoUrl.parse(request.repoUrl());
        String commit = requireCommit(request.commit());
        String expectedDigest = request.contentDigest() == null ? null : request.contentDigest().trim();
        if (expectedDigest == null || !expectedDigest.matches("[0-9a-fA-F]{64}")) {
            throw new DomainBadRequestException("error.skill.import.digest.invalid");
        }
        if (request.namespace() == null || request.namespace().isBlank()) {
            throw new DomainBadRequestException("error.namespace.slug.notFound", request.namespace());
        }

        GitCodeSnapshotMaterializer.Snapshot snapshot =
                materializer.materialize(repo, commit, request.path());
        if (!snapshot.digest().equalsIgnoreCase(expectedDigest)) {
            throw new DomainBadRequestException("error.skill.import.snapshot.changed");
        }
        return publisher.publish(repo, request.branch(), commit, snapshot,
                request.namespace().trim(), parseVisibility(request.visibility()),
                request.displayName(), request.changelog(), actor, roles, context);
    }

    private static GitCodeImportResolveResponse.Selected toSelected(GitCodeSnapshotMaterializer.Snapshot snapshot) {
        List<GitCodeImportResolveResponse.File> files = snapshot.files().stream()
                .map(file -> new GitCodeImportResolveResponse.File(file.path(), file.size(), file.sha256()))
                .toList();
        return new GitCodeImportResolveResponse.Selected(snapshot.path(), snapshot.digest(),
                snapshot.fileCount(), snapshot.totalSize(), files);
    }

    private static SkillVisibility parseVisibility(String visibility) {
        if (visibility == null || visibility.isBlank()) {
            throw new DomainBadRequestException("error.skill.import.visibility.invalid", visibility);
        }
        try {
            return SkillVisibility.valueOf(visibility.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new DomainBadRequestException("error.skill.import.visibility.invalid", visibility);
        }
    }

    private static String commitOrNull(String commit) {
        if (commit == null || commit.isBlank()) {
            return null;
        }
        return requireCommit(commit);
    }

    private static String requireCommit(String commit) {
        String normalized = commit == null ? "" : commit.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{40}")) {
            throw new DomainBadRequestException("error.skill.import.commit.invalid");
        }
        return normalized;
    }
}
