package com.iflytek.skillhub.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Resolves the credentials the OBS adapter signs with.
 *
 * <p>CCE/ECS instances are expected to run with credentials taken from the metadata service
 * ({@value ObsStorageProperties#DEFAULT_METADATA_URL}), which returns an OpenStack-style temporary
 * security key: {@code {"credential":{"access":"..","secret":"..","securitytoken":".."}}}.
 * Those credentials carry a security token that must travel with every request (header on API
 * calls, {@code x-obs-security-token} query parameter on presigned URLs) and expire after roughly
 * one hour.
 *
 * <p>Contract implemented here, mirroring the reference deployment:
 * <ul>
 *   <li>Static access/secret keys, when configured, win over the metadata service.</li>
 *   <li>Temporary credentials are cached for {@code credentialTtl} (default 50 minutes) and then
 *       re-fetched, so the client is rebuilt before the one hour expiry.</li>
 *   <li>A failed refresh never breaks a running application: the previously obtained credentials
 *       are returned and a warning is logged. Retries are suppressed for a short window so an
 *       unavailable metadata service is not hammered once per request.</li>
 *   <li>Secrets and security tokens are never logged nor included in exception messages.</li>
 * </ul>
 */
final class ObsCredentialProvider {
    /**
     * Query parameter used by OBS for temporary credentials. Observed behaviour of
     * esdk-obs-java-bundle 3.26.9: a client built with a security token already injects this
     * parameter into {@code createTemporarySignature} URLs. Adding it through query parameters
     * produces the identical URL (verified against the SDK), so it is kept explicit here to
     * document the protocol requirement instead of relying on SDK internals.
     */
    static final String SECURITY_TOKEN_QUERY_PARAMETER = "x-obs-security-token";

    /** Delay before retrying a failed credential refresh so an outage is not amplified. */
    private static final Duration FAILED_REFRESH_RETRY_DELAY = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(ObsCredentialProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One set of signing credentials; {@code securityToken} is set for temporary credentials. */
    record Credentials(String accessKey, String secretKey, String securityToken) {
        boolean hasSecurityToken() {
            return securityToken != null && !securityToken.isBlank();
        }

        /**
         * Redacted on purpose: the default record rendering would put the secret key and the
         * security token into any log line or exception message that interpolates this value.
         */
        @Override
        public String toString() {
            return "ObsCredentials[accessKey=***, securityToken=" + (hasSecurityToken() ? "present" : "absent") + "]";
        }
    }

    /** Raw metadata response; kept tiny and injectable so tests never touch the network. */
    record MetadataResponse(int statusCode, String body) {}

    /** Fetches the metadata document. */
    @FunctionalInterface
    interface MetadataClient {
        MetadataResponse get(String url, Duration timeout) throws IOException, InterruptedException;
    }

    private final ObsStorageProperties properties;
    private final ObsStorageProperties.CredentialMode mode;
    private final MetadataClient metadataClient;
    private final Credentials staticCredentials;

    private Credentials cachedCredentials;
    private Instant cachedAt;
    private Instant refreshSuppressedUntil;

    ObsCredentialProvider(ObsStorageProperties properties) {
        this(properties, httpMetadataClient(properties.getMetadataTimeout()));
    }

    ObsCredentialProvider(ObsStorageProperties properties, MetadataClient metadataClient) {
        this.properties = properties;
        this.metadataClient = metadataClient;
        this.mode = properties.resolveCredentialMode();
        this.staticCredentials = mode == ObsStorageProperties.CredentialMode.STATIC
                ? new Credentials(properties.getAccessKey(), properties.getSecretKey(), null)
                : null;
        if (mode == ObsStorageProperties.CredentialMode.STATIC && !properties.hasStaticCredentials()) {
            // Deterministic misconfiguration: fail at startup instead of on the first upload.
            throw new IllegalStateException("OBS credentialMode=static requires both accessKey and secretKey");
        }
    }

    /** Effective credential source, resolved from the configured mode. */
    ObsStorageProperties.CredentialMode mode() {
        return mode;
    }

    /**
     * Current signing credentials.
     *
     * @throws IllegalStateException when no credentials can be obtained at all (static
     *         misconfiguration, or metadata service unavailable before the first successful load)
     */
    Credentials current() {
        if (mode == ObsStorageProperties.CredentialMode.STATIC) {
            return staticCredentials;
        }
        synchronized (this) {
            if (!needsRefresh()) {
                return cachedCredentials;
            }
            try {
                Credentials refreshed = fetchFromMetadataService();
                cachedCredentials = refreshed;
                cachedAt = Instant.now();
                refreshSuppressedUntil = null;
                log.info("Obtained OBS temporary credentials from the metadata service (security token {})",
                        refreshed.hasSecurityToken() ? "present" : "absent");
                return refreshed;
            } catch (RuntimeException exception) {
                if (cachedCredentials != null) {
                    refreshSuppressedUntil = Instant.now().plus(FAILED_REFRESH_RETRY_DELAY);
                    log.warn("Failed to refresh OBS temporary credentials; keeping the previously obtained "
                            + "credentials and retrying in {}s", FAILED_REFRESH_RETRY_DELAY.toSeconds());
                    log.debug("OBS credential refresh failure detail", exception);
                    return cachedCredentials;
                }
                throw exception;
            }
        }
    }

    private synchronized boolean needsRefresh() {
        if (cachedCredentials == null) {
            return true;
        }
        Instant now = Instant.now();
        if (refreshSuppressedUntil != null && now.isBefore(refreshSuppressedUntil)) {
            return false;
        }
        Duration ttl = properties.getCredentialTtl();
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            return true;
        }
        return !now.isBefore(cachedAt.plus(ttl));
    }

    private Credentials fetchFromMetadataService() {
        String url = properties.getMetadataUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("OBS credentialMode=metadata requires a metadata URL");
        }
        Duration timeout = properties.getMetadataTimeout();
        MetadataResponse response;
        try {
            response = metadataClient.get(url, timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading OBS credentials from the metadata service");
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "OBS metadata service is unreachable at " + url + ": " + failure.getClass().getSimpleName());
        }
        if (response == null) {
            throw new IllegalStateException("OBS metadata service returned no response");
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("OBS metadata service responded with HTTP " + response.statusCode());
        }
        return parseCredentials(response.body());
    }

    /**
     * Parses the OpenStack-style metadata document. Package-private so the JSON contract can be
     * tested without a metadata server.
     */
    static Credentials parseCredentials(String document) {
        if (document == null || document.isBlank()) {
            throw new IllegalStateException("OBS metadata service returned an empty credential document");
        }
        JsonNode root;
        try {
            root = JSON.readTree(document);
        } catch (IOException malformed) {
            throw new IllegalStateException("OBS metadata credential document is not valid JSON");
        }
        JsonNode credential = root == null ? null : root.path("credential");
        if (credential == null || !credential.isObject()) {
            throw new IllegalStateException("OBS metadata credential document has no 'credential' object");
        }
        String access = textOrNull(credential, "access");
        String secret = textOrNull(credential, "secret");
        if (access == null || secret == null) {
            throw new IllegalStateException("OBS metadata credential document is missing access or secret");
        }
        return new Credentials(access, secret, textOrNull(credential, "securitytoken"));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static MetadataClient httpMetadataClient(Duration connectTimeout) {
        Duration effectiveConnectTimeout = connectTimeout != null && !connectTimeout.isNegative() && !connectTimeout.isZero()
                ? connectTimeout
                : Duration.ofSeconds(5);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(effectiveConnectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (url, timeout) -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout != null && !timeout.isNegative() && !timeout.isZero() ? timeout : effectiveConnectTimeout)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new MetadataResponse(response.statusCode(), response.body());
        };
    }
}
