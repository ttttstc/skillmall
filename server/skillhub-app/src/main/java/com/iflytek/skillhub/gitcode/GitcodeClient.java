package com.iflytek.skillhub.gitcode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.iflytek.skillhub.gitcode.GitcodeErrors.URL_INVALID;
import static com.iflytek.skillhub.gitcode.GitcodeErrors.RATE_LIMITED;
import static com.iflytek.skillhub.gitcode.GitcodeErrors.REPO_NOT_FOUND;
import static com.iflytek.skillhub.gitcode.GitcodeErrors.SKILL_MD_NOT_FOUND;
import static com.iflytek.skillhub.gitcode.GitcodeErrors.TOO_LARGE;
import static com.iflytek.skillhub.gitcode.GitcodeErrors.TOO_MANY_FILES;
import static com.iflytek.skillhub.gitcode.GitcodeErrors.UNREACHABLE;

/**
 * Reads public GitCode repositories through the anonymous REST API.
 *
 * <p>Archive downloads are deliberately unused: {@code contents} listings plus base64 file reads
 * give the import a tree it can bound, while an archive would have to be downloaded whole before
 * any limit could be applied. Every request is restricted to the documented GitCode hosts, must
 * resolve to a public address, and runs inside a connect/request/total budget.</p>
 */
@Component
public class GitcodeClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    static final Duration TOTAL_BUDGET = Duration.ofSeconds(60);
    /** A directory listing larger than this cannot describe a package the importer would accept. */
    static final long MAX_DIRECTORY_BODY_BYTES = 8L * 1024 * 1024;
    /** Base64 expands by 4/3, so the JSON envelope is bounded separately from the decoded file. */
    static final long MAX_FILE_BODY_BYTES = 8L * 1024 * 1024;
    static final long MAX_SINGLE_FILE_BYTES = 2L * 1024 * 1024;

    private static final String API_BASE = "https://api.gitcode.com/api/v5";
    private static final Set<String> ALLOWED_HOSTS = Set.of("api.gitcode.com", "raw.gitcode.com", "gitcode.com");
    private static final int PAGE_SIZE = 100;
    /** The git trees endpoint caps one page at 100 entries, same as the list endpoints. */
    private static final int TREE_PAGE_SIZE = PAGE_SIZE;
    private static final Logger log = LoggerFactory.getLogger(GitcodeClient.class);

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    /**
     * Spring constructor. It is annotated because the injectable HTTP client constructor below is
     * only for tests, and two unannotated constructors leave Spring without a candidate.
     */
    @Autowired
    public GitcodeClient(ObjectMapper objectMapper, Clock clock) {
        this(objectMapper, clock, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), REQUEST_TIMEOUT);
    }

    GitcodeClient(ObjectMapper objectMapper, Clock clock, HttpClient httpClient, Duration requestTimeout) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
    }

    /** Total time allowance shared by every call of one inspect or import operation. */
    public record Budget(Instant deadline) {

        public static Budget startingNow(Clock clock) {
            return new Budget(clock.instant().plus(TOTAL_BUDGET));
        }

        void assertRemaining(Clock clock) {
            if (clock.instant().isAfter(deadline)) {
                throw new DomainBadRequestException(UNREACHABLE);
            }
        }
    }

    /** One branch of the repository; {@code commitSha} is the branch head. */
    public record Branch(String name, String commitSha, boolean defaultBranch) {
    }

    /** One tag of the repository; {@code commitSha} is the tagged commit. */
    public record TagRef(String name, String commitSha) {
    }

    /** One directory entry as returned by the contents API. */
    public record Entry(String type, String name, String path) {

        public boolean isDirectory() {
            return "dir".equals(type);
        }

        public boolean isFile() {
            return "file".equals(type);
        }
    }

    /** Decoded content of one repository file. */
    public record FileContent(String path, long size, byte[] content) {
    }

    public List<Branch> listBranches(Budget budget, String owner, String repository) {
        URI uri = URI.create(API_BASE + "/repos/" + encode(owner) + "/" + encode(repository)
                + "/branches?per_page=" + PAGE_SIZE);
        RawResponse response = execute(budget, uri, MAX_DIRECTORY_BODY_BYTES, TOO_MANY_FILES);
        if (repositoryUnavailable(response.status())) {
            throw new DomainNotFoundException(REPO_NOT_FOUND);
        }
        JsonNode root = readArray(response, uri);
        List<Branch> branches = new ArrayList<>();
        for (JsonNode node : root) {
            String name = text(node, "name");
            String commitSha = text(node.path("commit"), "sha");
            if (name == null || name.isBlank() || commitSha == null || commitSha.isBlank()) {
                throw unreachable(uri, "branch entry without name or commit sha");
            }
            branches.add(new Branch(name, commitSha, node.path("default_branch").asBoolean(false)));
        }
        return branches;
    }

    public List<TagRef> listTags(Budget budget, String owner, String repository) {
        URI uri = URI.create(API_BASE + "/repos/" + encode(owner) + "/" + encode(repository)
                + "/tags?per_page=" + PAGE_SIZE);
        RawResponse response = execute(budget, uri, MAX_DIRECTORY_BODY_BYTES, TOO_MANY_FILES);
        if (repositoryUnavailable(response.status())) {
            throw new DomainNotFoundException(REPO_NOT_FOUND);
        }
        JsonNode root = readArray(response, uri);
        List<TagRef> tags = new ArrayList<>();
        for (JsonNode node : root) {
            String name = text(node, "name");
            String commitSha = text(node.path("commit"), "sha");
            if (name == null || name.isBlank() || commitSha == null || commitSha.isBlank()) {
                throw unreachable(uri, "tag entry without name or commit sha");
            }
            tags.add(new TagRef(name, commitSha));
        }
        return tags;
    }

    /**
     * Lists one repository directory. GitCode only treats a trailing slash as "list this directory",
     * so the path is always closed with one; the repository root is {@code contents/?ref=...}.
     */
    public List<Entry> listDirectory(Budget budget, String owner, String repository, String ref, String path) {
        StringBuilder uriBuilder = new StringBuilder(API_BASE)
                .append("/repos/").append(encode(owner)).append('/').append(encode(repository)).append("/contents/");
        if (path != null && !path.isBlank()) {
            for (String segment : path.split("/")) {
                if (!segment.isBlank()) {
                    uriBuilder.append(encode(segment)).append('/');
                }
            }
        }
        uriBuilder.append("?ref=").append(encode(ref));
        URI uri = URI.create(uriBuilder.toString());
        RawResponse response = execute(budget, uri, MAX_DIRECTORY_BODY_BYTES, TOO_MANY_FILES);
        if (response.status() == 404) {
            throw new DomainNotFoundException(SKILL_MD_NOT_FOUND);
        }
        JsonNode root = readArray(response, uri);
        List<Entry> entries = new ArrayList<>();
        for (JsonNode node : root) {
            String type = text(node, "type");
            String name = text(node, "name");
            String entryPath = text(node, "path");
            if (type == null || name == null || entryPath == null) {
                throw unreachable(uri, "directory entry without type, name or path");
            }
            entries.add(new Entry(type, name, entryPath));
        }
        return entries;
    }

    /** Reads one file; content above {@link #MAX_SINGLE_FILE_BYTES} is rejected instead of truncated. */
    /**
     * The whole tree of one revision, page by page.
     *
     * <p>Listing directory by directory costs one request per directory, which the anonymous API
     * rate-limits quickly on real repositories (HTTP 429). The git trees endpoint returns paths
     * in flat pages of at most 100 entries, so a repository needs a handful of requests instead.
     * Entries are normalised to the contents-API vocabulary the scanner already understands.</p>
     */
    public List<Entry> tree(Budget budget, String owner, String repository, String ref) {
        List<Entry> result = new ArrayList<>();
        for (int page = 1; ; page++) {
            URI uri = URI.create(API_BASE + "/repos/" + encode(owner) + "/" + encode(repository)
                    + "/git/trees/" + encode(ref) + "?recursive=1&per_page=" + TREE_PAGE_SIZE
                    + "&page=" + page);
            RawResponse response = execute(budget, uri, MAX_DIRECTORY_BODY_BYTES, TOO_MANY_FILES);
            if (repositoryUnavailable(response.status())) {
                throw new DomainNotFoundException(REPO_NOT_FOUND);
            }
            JsonNode root = readJson(response.body(), uri);
            JsonNode entries = root.path("tree");
            if (!entries.isArray()) {
                throw unreachable(uri, "tree response without a tree array");
            }
            if (entries.isEmpty()) {
                return result;
            }
            for (JsonNode node : entries) {
                String type = text(node, "type");
                String name = text(node, "name");
                String path = text(node, "path");
                if (type == null || name == null || path == null) {
                    continue;
                }
                String normalised = switch (type) {
                    case "tree", "dir", "directory" -> "dir";
                    case "blob", "file" -> "file";
                    default -> type;
                };
                result.add(new Entry(normalised, name, path));
            }
            if (entries.size() < TREE_PAGE_SIZE) {
                return result;
            }
        }
    }

    public FileContent readFile(Budget budget, String owner, String repository, String ref, String path) {
        StringBuilder uriBuilder = new StringBuilder(API_BASE)
                .append("/repos/").append(encode(owner)).append('/').append(encode(repository)).append("/contents/");
        String[] segments = path.split("/");
        for (int index = 0; index < segments.length; index++) {
            if (segments[index].isBlank()) {
                continue;
            }
            uriBuilder.append(encode(segments[index]));
            if (index < segments.length - 1) {
                uriBuilder.append('/');
            }
        }
        uriBuilder.append("?ref=").append(encode(ref));
        URI uri = URI.create(uriBuilder.toString());
        RawResponse response = execute(budget, uri, MAX_FILE_BODY_BYTES, TOO_LARGE);
        if (response.status() == 404) {
            throw new DomainNotFoundException(SKILL_MD_NOT_FOUND);
        }
        if (!isSuccess(response.status())) {
            throw unreachable(uri, "HTTP " + response.status());
        }
        JsonNode node = readJson(response.body(), uri);
        if (node.isArray()) {
            throw unreachable(uri, "expected a file but the contents API returned a directory listing");
        }
        long declaredSize = node.path("size").asLong(-1);
        if (declaredSize > MAX_SINGLE_FILE_BYTES) {
            throw new DomainBadRequestException(TOO_LARGE);
        }
        String encoded = text(node, "content");
        if (encoded == null) {
            throw unreachable(uri, "file content is missing");
        }
        byte[] content;
        try {
            content = Base64.getMimeDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw unreachable(uri, "file content is not valid base64");
        }
        if (content.length > MAX_SINGLE_FILE_BYTES) {
            throw new DomainBadRequestException(TOO_LARGE);
        }
        String entryPath = text(node, "path");
        return new FileContent(entryPath == null ? path : entryPath, content.length, content);
    }

    private record RawResponse(int status, byte[] body) {
    }

    private RawResponse execute(Budget budget, URI uri, long maxBytes, String tooLargeCode) {
        budget.assertRemaining(clock);
        assertAllowedUri(uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 429) {
                    // Anonymous reads are rate-limited per client; surface it as its own code so the
                    // wizard can tell the user to retry instead of reporting a dead host.
                    throw new DomainBadRequestException(RATE_LIMITED);
                }
                return new RawResponse(response.statusCode(), readBounded(body, maxBytes, tooLargeCode));
            }
        } catch (DomainBadRequestException exception) {
            throw exception;
        } catch (IOException exception) {
            throw unreachable(uri, exception.getClass().getSimpleName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DomainBadRequestException(UNREACHABLE);
        } catch (RuntimeException exception) {
            throw unreachable(uri, exception.getClass().getSimpleName());
        }
    }

    private byte[] readBounded(InputStream input, long maxBytes, String tooLargeCode) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new DomainBadRequestException(tooLargeCode);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void assertAllowedUri(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) {
            throw new DomainBadRequestException(URL_INVALID);
        }
        int port = uri.getPort();
        if (port != -1 && port != 443) {
            throw new DomainBadRequestException(URL_INVALID);
        }
        String host = uri.getHost();
        if (host == null) {
            throw new DomainBadRequestException(URL_INVALID);
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!ALLOWED_HOSTS.contains(normalizedHost)) {
            throw new DomainBadRequestException(URL_INVALID);
        }
        assertPublicHost(normalizedHost);
    }

    /** Defence in depth against a whitelisted name resolving to an internal address. */
    private void assertPublicHost(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress() || isUniqueLocalIpv6(address)) {
                    log.warn("Refusing GitCode request to non-public address class: host={}", host);
                    throw new DomainBadRequestException(UNREACHABLE);
                }
            }
        } catch (UnknownHostException exception) {
            throw new DomainBadRequestException(UNREACHABLE);
        }
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return (bytes[0] & 0xfe) == 0xfc;
    }

    private static boolean repositoryUnavailable(int status) {
        return status == 404 || status == 401 || status == 403 || status == 451;
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private JsonNode readArray(RawResponse response, URI uri) {
        if (!isSuccess(response.status())) {
            throw unreachable(uri, "HTTP " + response.status());
        }
        JsonNode root = readJson(response.body(), uri);
        if (!root.isArray()) {
            throw unreachable(uri, "expected a JSON array");
        }
        return root;
    }

    private JsonNode readJson(byte[] body, URI uri) {
        try {
            return objectMapper.readTree(body);
        } catch (IOException exception) {
            throw unreachable(uri, "response is not valid JSON");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static DomainBadRequestException unreachable(URI uri, String reason) {
        log.warn("GitCode API call failed: host={} path={} reason={}",
                uri.getHost(), uri.getRawPath(), reason);
        return new DomainBadRequestException(UNREACHABLE);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
