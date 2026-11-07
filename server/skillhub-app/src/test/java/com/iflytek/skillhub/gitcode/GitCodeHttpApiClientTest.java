package com.iflytek.skillhub.gitcode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitCodeHttpApiClientTest {

    private static final String API = "https://gitcode.test/api/v5";
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void addressesNestedGroupRepositoriesWithAnEncodedNamespace() {
        List<URI> requested = new ArrayList<>();
        GitCodeHttpApiClient client = new GitCodeHttpApiClient(uri -> {
            requested.add(uri);
            return new GitCodeHttpApiClient.Response(200,
                    ("[{\"sha\":\"" + COMMIT + "\"}]").getBytes(StandardCharsets.UTF_8));
        }, mapper, API, 0, Duration.ZERO);

        client.head(new GitCodeRepoUrl("GitHub_Trending/skills13", "skills"));

        assertThat(requested.getFirst().toString())
                .isEqualTo(API + "/repos/GitHub_Trending%2Fskills13/skills/commits");
    }

    @Test
    void contentRequestsAlwaysPinTheCommitInsteadOfABranch() {
        List<URI> requested = new ArrayList<>();
        GitCodeHttpApiClient client = new GitCodeHttpApiClient(uri -> {
            requested.add(uri);
            return new GitCodeHttpApiClient.Response(200, "[]".getBytes(StandardCharsets.UTF_8));
        }, mapper, API, 0, Duration.ZERO);

        client.list(new GitCodeRepoUrl("openharmony", "docs"), "zh-cn", COMMIT);

        assertThat(requested.getFirst().toString())
                .isEqualTo(API + "/repos/openharmony/docs/contents/zh-cn?ref=" + COMMIT);
    }

    @Test
    void retriesRateLimitedResponsesBeforeFailingExplicitly() {
        Deque<Integer> statuses = new ArrayDeque<>(List.of(429, 429, 200));
        GitCodeHttpApiClient client = new GitCodeHttpApiClient(uri ->
                new GitCodeHttpApiClient.Response(statuses.isEmpty() ? 200 : statuses.removeFirst(),
                        "[]".getBytes(StandardCharsets.UTF_8)),
                mapper, API, 2, Duration.ZERO);

        assertThat(client.list(new GitCodeRepoUrl("openharmony", "docs"), "", COMMIT)).isEmpty();
        assertThat(statuses).isEmpty();
    }

    @Test
    void reportsPersistentRateLimitingAsItsOwnFailure() {
        GitCodeHttpApiClient client = new GitCodeHttpApiClient(uri ->
                new GitCodeHttpApiClient.Response(429, new byte[0]), mapper, API, 1, Duration.ZERO);

        assertThatThrownBy(() -> client.list(new GitCodeRepoUrl("openharmony", "docs"), "", COMMIT))
                .isInstanceOfSatisfying(GitCodeApiException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(GitCodeApiException.Kind.RATE_LIMITED));
    }

    @Test
    void mapsMissingAndForbiddenRepositoriesToDistinctKinds() {
        GitCodeHttpApiClient missing = new GitCodeHttpApiClient(
                uri -> new GitCodeHttpApiClient.Response(404, new byte[0]), mapper, API, 0, Duration.ZERO);
        GitCodeHttpApiClient forbidden = new GitCodeHttpApiClient(
                uri -> new GitCodeHttpApiClient.Response(403, new byte[0]), mapper, API, 0, Duration.ZERO);

        assertThatThrownBy(() -> missing.head(new GitCodeRepoUrl("openharmony", "docs")))
                .isInstanceOfSatisfying(GitCodeApiException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(GitCodeApiException.Kind.NOT_FOUND));
        assertThatThrownBy(() -> forbidden.head(new GitCodeRepoUrl("openharmony", "docs")))
                .isInstanceOfSatisfying(GitCodeApiException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(GitCodeApiException.Kind.FORBIDDEN));
    }

    @Test
    void decodesBase64FileContentAndRejectsNonBase64Responses() {
        String body = "{\"type\":\"file\",\"encoding\":\"base64\",\"content\":\"aGVsbG8K\"}";
        GitCodeHttpApiClient client = new GitCodeHttpApiClient(
                uri -> new GitCodeHttpApiClient.Response(200, body.getBytes(StandardCharsets.UTF_8)),
                mapper, API, 0, Duration.ZERO);

        assertThat(new String(client.readFile(new GitCodeRepoUrl("openharmony", "docs"),
                "README.md", COMMIT), StandardCharsets.UTF_8)).isEqualTo("hello\n");

        GitCodeHttpApiClient plain = new GitCodeHttpApiClient(
                uri -> new GitCodeHttpApiClient.Response(200,
                        "{\"type\":\"file\",\"encoding\":\"none\",\"content\":\"x\"}".getBytes(StandardCharsets.UTF_8)),
                mapper, API, 0, Duration.ZERO);
        assertThatThrownBy(() -> plain.readFile(new GitCodeRepoUrl("openharmony", "docs"), "README.md", COMMIT))
                .isInstanceOfSatisfying(GitCodeApiException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(GitCodeApiException.Kind.INVALID_RESPONSE));
    }
}
