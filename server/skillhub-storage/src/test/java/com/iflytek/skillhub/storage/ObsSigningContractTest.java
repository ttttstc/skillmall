package com.iflytek.skillhub.storage;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the OBS signing contract against the real SDK without any network access:
 * {@code createTemporarySignature} signs locally, so the produced URLs can be inspected offline.
 *
 * <p>These assertions are the OBS counterpart of the S3 SigV4 contract and would fail if somebody
 * routed OBS back through the AWS SDK presigner, or if a dependency upgrade changed the format.
 */
class ObsSigningContractTest {

    @Test
    void presignedGetUsesTheObsQueryFormatInsteadOfSigV4() {
        try (ObsApi api = api(staticCredentials())) {
            URI url = URI.create(api.presignGet("packages/中文 package.zip", 600, null));
            Map<String, String> query = queryParameters(url);

            assertThat(query).containsKeys("AccessKeyId", "Expires", "Signature");
            assertThat(query).doesNotContainKeys("X-Amz-Signature", "X-Amz-Credential", "X-Amz-SignedHeaders");
            assertThat(query.get("AccessKeyId")).isEqualTo("static-ak");
            assertThat(url.getHost()).startsWith("skillhub.");
        }
    }

    @Test
    void presignedGetExpiresAtAnAbsoluteSecondTimestamp() {
        long before = System.currentTimeMillis() / 1000;
        try (ObsApi api = api(staticCredentials())) {
            long expires = Long.parseLong(queryParameters(URI.create(api.presignGet("packages/demo.zip", 600, null)))
                    .get("Expires"));

            assertThat(expires).isBetween(before + 590, before + 620);
        }
    }

    @Test
    void temporaryCredentialsPutTheSecurityTokenIntoTheSignedQuery() {
        ObsCredentialProvider.Credentials temporary =
                new ObsCredentialProvider.Credentials("static-ak", "static-sk", "temporary-token");
        try (ObsApi api = api(temporary)) {
            String url = api.presignGet("packages/demo.zip", 600, null);

            assertThat(queryParameters(URI.create(url)))
                    .containsEntry(ObsCredentialProvider.SECURITY_TOKEN_QUERY_PARAMETER, "temporary-token");
            // The token must appear exactly once; a duplicated parameter would break the signature.
            assertThat(countOccurrences(URI.create(url).getRawQuery(),
                    ObsCredentialProvider.SECURITY_TOKEN_QUERY_PARAMETER + "=")).isEqualTo(1);
        }
    }

    @Test
    void downloadFilenameIsSignedAsResponseContentDisposition() {
        try (ObsApi api = api(staticCredentials())) {
            URI url = URI.create(api.presignGet("packages/demo.zip", 600,
                    "attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87%20package.zip"));

            assertThat(queryParameters(url))
                    .containsEntry("response-content-disposition",
                            "attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87%20package.zip");
        }
    }

    @Test
    void presignedPutSignatureDependsOnTheContentType() {
        try (ObsApi api = api(staticCredentials())) {
            String zipSignature = queryParameters(URI.create(
                    api.presignPut("staging/demo.zip", 600, "application/zip").url())).get("Signature");
            String octetStreamSignature = queryParameters(URI.create(
                    api.presignPut("staging/demo.zip", 600, "application/octet-stream").url())).get("Signature");

            // OBS folds Content-Type into the string to sign: this is why the uploader must send the
            // very same header value the adapter signed with.
            assertThat(zipSignature).isNotBlank().isNotEqualTo(octetStreamSignature);
        }
    }

    @Test
    void presignedPutReturnsTheContentTypeHeaderTheUploaderMustSend() {
        try (ObsApi api = api(staticCredentials())) {
            PresignedUpload upload = api.presignPut("staging/demo.zip", 600, "application/zip");

            assertThat(upload.headers())
                    .containsEntry("Content-Type", "application/zip")
                    .doesNotContainKey("Host");
        }
    }

    @Test
    void presignedUrlsAreSignedAgainstTheConfiguredPublicEndpoint() {
        ObsStorageProperties properties = properties();
        // The signing host, not the in-cluster endpoint, must appear in the URL.
        properties.setPublicEndpoint("https://obs-public.cn-north-4.myhuaweicloud.com");
        try (ObsApi api = new SdkObsApiFactory(properties).create(staticCredentials())) {
            URI url = URI.create(api.presignGet("packages/demo.zip", 600, null));

            // OBS requires the bucket name in the host, so the bucket is prefixed to whatever
            // endpoint is configured: <bucket>.<public endpoint host>. A custom download domain
            // must therefore be an OBS service domain, or a CNAME that still resolves
            // <bucket>.<domain> - the public endpoint is not a free-form CDN alias.
            assertThat(url.getHost()).isEqualTo("skillhub.obs-public.cn-north-4.myhuaweicloud.com");
        }
    }

    private ObsApi api(ObsCredentialProvider.Credentials credentials) {
        return new SdkObsApiFactory(properties()).create(credentials);
    }

    private ObsStorageProperties properties() {
        ObsStorageProperties properties = new ObsStorageProperties();
        properties.setBucket("skillhub");
        properties.setEndpoint("https://obs.cn-north-4.myhuaweicloud.com");
        properties.setAccessKey("static-ak");
        properties.setSecretKey("static-sk");
        properties.setConnectionTimeout(Duration.ofSeconds(2));
        properties.setSocketTimeout(Duration.ofSeconds(2));
        return properties;
    }

    private ObsCredentialProvider.Credentials staticCredentials() {
        return new ObsCredentialProvider.Credentials("static-ak", "static-sk", null);
    }

    private static Map<String, String> queryParameters(URI url) {
        Map<String, String> parameters = new LinkedHashMap<>();
        String query = url.getRawQuery();
        if (query == null || query.isEmpty()) {
            return parameters;
        }
        for (String field : query.split("&")) {
            int separator = field.indexOf('=');
            String name = separator < 0 ? field : field.substring(0, separator);
            String value = separator < 0 ? "" : field.substring(separator + 1);
            parameters.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return parameters;
    }

    private static long countOccurrences(String text, String needle) {
        return Arrays.stream(text.split("&", -1)).filter(field -> field.startsWith(needle)).count();
    }
}
