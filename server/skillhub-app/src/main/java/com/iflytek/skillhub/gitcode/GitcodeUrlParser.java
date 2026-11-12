package com.iflytek.skillhub.gitcode;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses a GitCode web address into the repository coordinates the importer needs.
 *
 * <p>Accepted forms (scheme {@code http} or {@code https} may be omitted):</p>
 * <ul>
 *   <li>{@code https://gitcode.com/{owner}/{repo}}</li>
 *   <li>{@code https://gitcode.com/{owner}/{repo}.git}</li>
 *   <li>{@code https://gitcode.com/{owner}/{repo}/tree/{ref}}</li>
 *   <li>{@code https://gitcode.com/{owner}/{repo}/tree/{ref}/{path}}</li>
 * </ul>
 *
 * <p>Everything else — another host, a bare IP literal, embedded credentials, an absolute or
 * traversing path — is rejected with {@code error.gitcode.url.invalid}. The parser never resolves
 * a ref: whether {@code ref} is a branch, tag, or commit is decided against the API.</p>
 */
@Component
public class GitcodeUrlParser {

    /** The only host an import address may point at; API calls are built from parts, not from this URL. */
    public static final String ALLOWED_HOST = "gitcode.com";

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<String> ALLOWED_PORTS = Set.of("80", "443");
    private static final String TREE_SEGMENT = "tree";
    private static final int MAX_REF_LENGTH = 256;
    private static final int MAX_PATH_LENGTH = 512;
    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9_-]{0,98}[A-Za-z0-9])?");
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final String GIT_SUFFIX = ".git";

    /**
     * Repository coordinates taken from the address; {@code ref} and {@code path} are empty when the
     * address did not carry them.
     */
    public record ParsedRepositoryUrl(String owner, String repository, String ref, String path) {

        public ParsedRepositoryUrl {
            path = path == null ? "" : path;
        }

        public boolean hasRef() {
            return ref != null && !ref.isBlank();
        }

        public boolean hasPath() {
            return !path.isBlank();
        }
    }

    public ParsedRepositoryUrl parse(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw invalidUrl();
        }
        String candidate = rawUrl.trim();
        if (!candidate.contains("://")) {
            candidate = "https://" + candidate;
        }
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException exception) {
            throw invalidUrl();
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw invalidUrl();
        }
        if (uri.getRawUserInfo() != null) {
            throw invalidUrl();
        }
        if (uri.getPort() != -1 && !ALLOWED_PORTS.contains(Integer.toString(uri.getPort()))) {
            throw invalidUrl();
        }
        String host = uri.getHost();
        if (host == null) {
            throw invalidUrl();
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.endsWith(".")) {
            normalizedHost = normalizedHost.substring(0, normalizedHost.length() - 1);
        }
        if (!ALLOWED_HOST.equals(normalizedHost)) {
            throw invalidUrl();
        }

        List<String> segments = splitPath(uri.getPath());
        if (segments.size() < 2) {
            throw invalidUrl();
        }
        String owner = segments.get(0);
        String repository = stripGitSuffix(segments.get(1));
        if (!OWNER.matcher(owner).matches() || !REPOSITORY.matcher(repository).matches()
                || ".".equals(repository) || "..".equals(repository)) {
            throw invalidUrl();
        }

        String ref = null;
        String path = "";
        if (segments.size() > 2) {
            if (!TREE_SEGMENT.equals(segments.get(2)) || segments.size() < 4) {
                throw invalidUrl();
            }
            ref = segments.get(3);
            validateRef(ref);
            path = joinPath(segments.subList(4, segments.size()));
        }
        return new ParsedRepositoryUrl(owner, repository, ref, path);
    }

    private static List<String> splitPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw invalidUrl();
        }
        List<String> segments = new ArrayList<>();
        for (String segment : rawPath.split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            if (".".equals(segment) || "..".equals(segment) || hasUnsafeCharacter(segment)) {
                throw invalidUrl();
            }
            segments.add(segment);
        }
        return segments;
    }

    private static String stripGitSuffix(String repository) {
        if (repository.toLowerCase(Locale.ROOT).endsWith(GIT_SUFFIX)) {
            return repository.substring(0, repository.length() - GIT_SUFFIX.length());
        }
        return repository;
    }

    private static void validateRef(String ref) {
        if (ref == null || ref.isBlank() || ref.length() > MAX_REF_LENGTH
                || ref.contains("..") || hasUnsafeCharacter(ref)) {
            throw invalidUrl();
        }
    }

    private static String joinPath(List<String> segments) {
        if (segments.isEmpty()) {
            return "";
        }
        String path = String.join("/", segments);
        if (path.length() > MAX_PATH_LENGTH) {
            throw invalidUrl();
        }
        return path;
    }

    private static boolean hasUnsafeCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f || character == '\\' || character == ':'
                    || Character.isWhitespace(character)) {
                return true;
            }
        }
        return false;
    }

    private static DomainBadRequestException invalidUrl() {
        return new DomainBadRequestException("error.gitcode.url.invalid");
    }
}
