package com.iflytek.skillhub.gitcode;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A repository coordinate that SkillMall is willing to import from.
 *
 * <p>Only public GitCode repositories are accepted. Every other host, scheme or shape is rejected
 * with an explicit error code so the caller can tell "unsupported source" apart from "bad URL"
 * instead of receiving an opaque failure.
 *
 * <p>GitCode serves repositories from optional nested groups (for example
 * {@code https://gitcode.com/GitHub_Trending/skills13/skills}), so the owner part is a namespace
 * path rather than a single segment. The repository name is always the last segment.
 */
public record GitCodeRepoUrl(String owner, String repo) {

    public static final String HOST = "gitcode.com";

    /** Deepest group nesting accepted; deeper paths are not real GitCode repository roots. */
    private static final int MAX_NAMESPACE_SEGMENTS = 2;

    /** Second path segments that address a repository view rather than a nested group. */
    private static final Set<String> VIEW_SEGMENTS = Set.of(
            "tree", "blob", "raw", "src", "commits", "commit", "issues", "pulls", "merge_requests",
            "wiki", "releases", "tags", "branches", "settings", "graphs", "network", "forks",
            "stargazers", "watchers", "archive");

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern REPO_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");

    public GitCodeRepoUrl {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner");
        }
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("repo");
        }
    }

    /** Canonical, credential-free form used for persistence and display. */
    public String canonicalUrl() {
        return "https://" + HOST + "/" + owner + "/" + repo;
    }

    public String ownerRepoKey() {
        return owner + "/" + repo;
    }

    public static GitCodeRepoUrl parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DomainBadRequestException("error.skill.import.url.invalid");
        }
        String candidate = raw.trim();
        if (candidate.chars().anyMatch(Character::isWhitespace)) {
            throw new DomainBadRequestException("error.skill.import.url.invalid");
        }
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException exception) {
            throw new DomainBadRequestException("error.skill.import.url.invalid");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            throw new DomainBadRequestException("error.skill.import.url.invalid");
        }
        // Only GitCode over https is a supported source; other hosts and schemes are named
        // explicitly as unsupported rather than reported as a malformed URL.
        if (!"https".equalsIgnoreCase(scheme)) {
            throw new DomainBadRequestException("error.skill.import.source.unsupported", scheme);
        }
        if (!HOST.equals(host.toLowerCase(Locale.ROOT))) {
            throw new DomainBadRequestException("error.skill.import.source.unsupported", host);
        }
        if (uri.getUserInfo() != null || uri.getPort() != -1
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new DomainBadRequestException("error.skill.import.url.invalid");
        }

        String path = uri.getPath() == null ? "" : uri.getPath();
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        if (segments.size() < 2 || segments.size() > MAX_NAMESPACE_SEGMENTS + 1) {
            throw new DomainBadRequestException("error.skill.import.url.invalid");
        }
        // A nested group and a repository view share the same shape; refuse the view names we can
        // recognise so a browser address bar copy keeps producing a URL error, not a lookup.
        if (segments.size() == 3
                && VIEW_SEGMENTS.contains(segments.get(1).toLowerCase(Locale.ROOT))) {
            throw new DomainBadRequestException("error.skill.import.url.invalid");
        }
        String repo = segments.getLast();
        if (repo.endsWith(".git")) {
            repo = repo.substring(0, repo.length() - 4);
        }
        for (String group : segments.subList(0, segments.size() - 1)) {
            if (!SEGMENT.matcher(group).matches() || group.contains("..")) {
                throw new DomainBadRequestException("error.skill.import.url.invalid");
            }
        }
        if (!REPO_SEGMENT.matcher(repo).matches() || repo.contains("..")) {
            throw new DomainBadRequestException("error.skill.import.url.invalid");
        }
        return new GitCodeRepoUrl(String.join("/", segments.subList(0, segments.size() - 1)), repo);
    }
}
