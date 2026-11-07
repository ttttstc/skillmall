package com.iflytek.skillhub.gitcode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Anonymous reader for the public GitCode v5 API.
 *
 * <p>Content is always requested with an explicit {@code ref}; the importer never lets this client
 * fall back to a branch name, because a moving branch would silently change what is published.
 */
@Component
public class GitCodeHttpApiClient implements GitCodeApiClient {

    /** Transport seam so URL building and response parsing can be exercised without the network. */
    @FunctionalInterface
    public interface HttpSender {
        Response send(URI uri);
    }

    public record Response(int status, byte[] body) {

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final HttpSender sender;
    private final ObjectMapper mapper;
    private final String apiBaseUrl;
    private final int maxRateLimitRetries;
    private final Duration rateLimitBackoff;

    @Autowired
    public GitCodeHttpApiClient(ObjectMapper mapper, GitCodeImportProperties properties) {
        this(new JavaHttpSender(properties.getRequestTimeout()), mapper, properties.getApiBaseUrl(),
                properties.getMaxRateLimitRetries(), properties.getRateLimitBackoff());
    }

    public GitCodeHttpApiClient(HttpSender sender, ObjectMapper mapper, String apiBaseUrl) {
        this(sender, mapper, apiBaseUrl, 2, Duration.ofMillis(800));
    }

    public GitCodeHttpApiClient(HttpSender sender, ObjectMapper mapper, String apiBaseUrl,
                                int maxRateLimitRetries, Duration rateLimitBackoff) {
        this.sender = sender;
        this.mapper = mapper;
        this.apiBaseUrl = apiBaseUrl.endsWith("/")
                ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
                : apiBaseUrl;
        this.maxRateLimitRetries = Math.max(0, maxRateLimitRetries);
        this.rateLimitBackoff = rateLimitBackoff == null ? Duration.ZERO : rateLimitBackoff;
    }

    private Response send(URI uri) {
        try {
            return sender.send(uri);
        } catch (RuntimeException exception) {
            throw new GitCodeApiException(GitCodeApiException.Kind.UNAVAILABLE,
                    "GitCode request failed: " + uri, exception);
        }
    }

    private void sleepBeforeRetry(int attempt) {
        long millis = rateLimitBackoff.toMillis() * (attempt + 1L);
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitCodeApiException(GitCodeApiException.Kind.UNAVAILABLE,
                    "Interrupted while waiting for the GitCode rate limit", exception);
        }
    }

    @Override
    public RepositoryHead head(GitCodeRepoUrl repo) {
        JsonNode commits = getJson(repoPath(repo) + "/commits");
        JsonNode first = commits.isArray() ? (commits.isEmpty() ? null : commits.get(0)) : commits;
        String commit = text(first, "sha");
        if (commit == null || !commit.matches("[0-9a-f]{40}")) {
            throw new GitCodeApiException(GitCodeApiException.Kind.INVALID_RESPONSE,
                    "Missing commit sha for " + repo.canonicalUrl());
        }
        return new RepositoryHead(commit, resolveDefaultBranch(repo, commit));
    }

    private String resolveDefaultBranch(GitCodeRepoUrl repo, String headCommit) {
        try {
            JsonNode branches = getJson(repoPath(repo) + "/branches?per_page=100");
            if (!branches.isArray()) {
                return null;
            }
            List<String> matching = new ArrayList<>();
            for (JsonNode branch : branches) {
                String name = text(branch, "name");
                if (name == null) {
                    continue;
                }
                JsonNode commitNode = branch.path("commit");
                String id = text(commitNode, "id");
                if (id == null) {
                    id = text(commitNode.path("commit"), "id");
                }
                if (id == null) {
                    id = text(commitNode, "sha");
                }
                if (headCommit.equals(id)) {
                    matching.add(name);
                }
            }
            for (String preferred : List.of("main", "master")) {
                if (matching.contains(preferred)) {
                    return preferred;
                }
            }
            return matching.isEmpty() ? null : matching.get(0);
        } catch (GitCodeApiException ignored) {
            // Branch naming is descriptive only; the commit is the pinned fact.
            return null;
        }
    }

    @Override
    public List<DirectoryEntry> list(GitCodeRepoUrl repo, String path, String ref) {
        String suffix = path == null || path.isEmpty() ? "" : "/" + encodePath(path);
        JsonNode node = getJson(repoPath(repo) + "/contents" + suffix + "?ref=" + ref);
        if (!node.isArray()) {
            throw new GitCodeApiException(GitCodeApiException.Kind.INVALID_RESPONSE,
                    "Directory listing expected for " + path + " at " + ref);
        }
        List<DirectoryEntry> entries = new ArrayList<>(node.size());
        for (JsonNode entry : node) {
            // GitCode omits "size" for directory listings; -1 marks "unknown" so callers never treat
            // a missing size as an empty file.
            long size = entry.hasNonNull("size") ? entry.get("size").asLong() : -1L;
            entries.add(new DirectoryEntry(
                    text(entry, "type"),
                    text(entry, "path"),
                    size));
        }
        return entries;
    }

    @Override
    public byte[] readFile(GitCodeRepoUrl repo, String path, String ref) {
        JsonNode node = getJson(repoPath(repo) + "/contents/" + encodePath(path) + "?ref=" + ref);
        if (!node.isObject() || !"file".equals(text(node, "type"))) {
            throw new GitCodeApiException(GitCodeApiException.Kind.INVALID_RESPONSE,
                    "File expected for " + path + " at " + ref);
        }
        String encoding = text(node, "encoding");
        String content = text(node, "content");
        if (content == null || !"base64".equalsIgnoreCase(encoding)) {
            throw new GitCodeApiException(GitCodeApiException.Kind.INVALID_RESPONSE,
                    "Unsupported encoding for " + path);
        }
        try {
            return Base64.getMimeDecoder().decode(content);
        } catch (IllegalArgumentException exception) {
            throw new GitCodeApiException(GitCodeApiException.Kind.INVALID_RESPONSE,
                    "Invalid base64 content for " + path, exception);
        }
    }

    private JsonNode getJson(String relativeUrl) {
        URI uri = URI.create(apiBaseUrl + relativeUrl);
        Response response = send(uri);
        // Anonymous GitCode requests are rate limited per client. Retrying briefly keeps one
        // user-triggered preview from failing mid-walk, and the caller still gets an explicit
        // "too many requests" error when the limit outlasts the retries.
        for (int attempt = 0; response.status() == 429 && attempt < maxRateLimitRetries; attempt++) {
            sleepBeforeRetry(attempt);
            response = send(uri);
        }
        int status = response.status();
        if (status == 429) {
            throw new GitCodeApiException(GitCodeApiException.Kind.RATE_LIMITED,
                    "GitCode rate limit reached for " + uri);
        }
        if (status == 404) {
            throw new GitCodeApiException(GitCodeApiException.Kind.NOT_FOUND, "Not found: " + uri);
        }
        if (status == 401 || status == 403) {
            throw new GitCodeApiException(GitCodeApiException.Kind.FORBIDDEN,
                    "Not public or access denied: " + uri);
        }
        if (status < 200 || status >= 300) {
            throw new GitCodeApiException(GitCodeApiException.Kind.UNAVAILABLE,
                    "GitCode responded " + status + " for " + uri);
        }
        try {
            return mapper.readTree(response.body());
        } catch (IOException exception) {
            throw new GitCodeApiException(GitCodeApiException.Kind.INVALID_RESPONSE,
                    "Unreadable GitCode response for " + uri, exception);
        }
    }

    /**
     * GitCode addresses a repository inside a nested group with the group path percent-encoded as a
     * single owner segment, so {@code GitHub_Trending/skills13} stays one path component.
     */
    private static String repoPath(GitCodeRepoUrl repo) {
        return "/repos/" + encodePath(repo.owner()).replace("/", "%2F") + "/" + encodePath(repo.repo());
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    /** Percent-encodes every path segment, keeping unreserved characters readable. */
    static String encodePath(String path) {
        StringBuilder builder = new StringBuilder(path.length());
        for (int index = 0; index < path.length(); index++) {
            char character = path.charAt(index);
            if (character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '-' || character == '_' || character == '.' || character == '~'
                    || character == '/') {
                builder.append(character);
            } else {
                for (byte raw : String.valueOf(character).getBytes(StandardCharsets.UTF_8)) {
                    builder.append('%').append(Character.toUpperCase(Character.forDigit((raw >> 4) & 0xF, 16)))
                            .append(Character.toUpperCase(Character.forDigit(raw & 0xF, 16)));
                }
            }
        }
        return builder.toString();
    }

    /** Blocking JDK HTTP transport; timeouts come from {@link GitCodeImportProperties}. */
    static final class JavaHttpSender implements HttpSender {

        private final HttpClient client;
        private final java.time.Duration timeout;

        JavaHttpSender(java.time.Duration timeout) {
            this.timeout = timeout;
            this.client = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }

        @Override
        public Response send(URI uri) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                return new Response(response.statusCode(), response.body());
            } catch (IOException exception) {
                throw new GitCodeApiException(GitCodeApiException.Kind.UNAVAILABLE,
                        "GitCode request failed: " + uri, exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new GitCodeApiException(GitCodeApiException.Kind.UNAVAILABLE,
                        "GitCode request interrupted: " + uri, exception);
            }
        }
    }
}
