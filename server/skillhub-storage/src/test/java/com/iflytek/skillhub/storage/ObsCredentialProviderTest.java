package com.iflytek.skillhub.storage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObsCredentialProviderTest {

    private static final String VALID_DOCUMENT = """
            {"credential":{"access":"temp-ak","secret":"temp-sk","securitytoken":"temp-token"}}
            """;

    @Test
    void staticKeysWinOverTheMetadataService() {
        ObsStorageProperties properties = properties(ObsStorageProperties.CredentialMode.AUTO);
        properties.setAccessKey("static-ak");
        properties.setSecretKey("static-sk");
        AtomicInteger metadataCalls = new AtomicInteger();
        ObsCredentialProvider provider = new ObsCredentialProvider(properties, (url, timeout) -> {
            metadataCalls.incrementAndGet();
            return new ObsCredentialProvider.MetadataResponse(200, VALID_DOCUMENT);
        });

        ObsCredentialProvider.Credentials credentials = provider.current();

        assertThat(credentials.accessKey()).isEqualTo("static-ak");
        assertThat(credentials.secretKey()).isEqualTo("static-sk");
        assertThat(credentials.hasSecurityToken()).isFalse();
        assertThat(metadataCalls).hasValue(0);
        assertThat(provider.mode()).isEqualTo(ObsStorageProperties.CredentialMode.STATIC);
    }

    @Test
    void autoModeFallsBackToMetadataWhenStaticKeysAreIncomplete() {
        ObsStorageProperties properties = properties(ObsStorageProperties.CredentialMode.AUTO);
        properties.setAccessKey("static-ak");
        properties.setSecretKey("  ");

        ObsCredentialProvider provider = new ObsCredentialProvider(properties,
                (url, timeout) -> new ObsCredentialProvider.MetadataResponse(200, VALID_DOCUMENT));

        assertThat(provider.mode()).isEqualTo(ObsStorageProperties.CredentialMode.METADATA);
        assertThat(provider.current().accessKey()).isEqualTo("temp-ak");
    }

    @Test
    void explicitStaticModeWithoutKeysFailsFast() {
        ObsStorageProperties properties = properties(ObsStorageProperties.CredentialMode.STATIC);

        assertThatThrownBy(() -> new ObsCredentialProvider(properties,
                (url, timeout) -> new ObsCredentialProvider.MetadataResponse(200, VALID_DOCUMENT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accessKey");
    }

    @Test
    void explicitMetadataModeIgnoresConfiguredStaticKeys() {
        ObsStorageProperties properties = properties(ObsStorageProperties.CredentialMode.METADATA);
        properties.setAccessKey("static-ak");
        properties.setSecretKey("static-sk");

        ObsCredentialProvider provider = new ObsCredentialProvider(properties,
                (url, timeout) -> new ObsCredentialProvider.MetadataResponse(200, VALID_DOCUMENT));

        assertThat(provider.mode()).isEqualTo(ObsStorageProperties.CredentialMode.METADATA);
        assertThat(provider.current().accessKey()).isEqualTo("temp-ak");
    }

    @Test
    void metadataDocumentProvidesAccessSecretAndSecurityToken() {
        ObsCredentialProvider.Credentials credentials = ObsCredentialProvider.parseCredentials(VALID_DOCUMENT);

        assertThat(credentials.accessKey()).isEqualTo("temp-ak");
        assertThat(credentials.secretKey()).isEqualTo("temp-sk");
        assertThat(credentials.securityToken()).isEqualTo("temp-token");
        assertThat(credentials.hasSecurityToken()).isTrue();
    }

    @Test
    void metadataDocumentWithoutSecurityTokenIsAccepted() {
        ObsCredentialProvider.Credentials credentials = ObsCredentialProvider.parseCredentials(
                "{\"credential\":{\"access\":\"ak\",\"secret\":\"sk\"}}");

        assertThat(credentials.hasSecurityToken()).isFalse();
    }

    @Test
    void metadataDocumentWithoutAccessIsRejected() {
        assertThatThrownBy(() -> ObsCredentialProvider.parseCredentials(
                "{\"credential\":{\"secret\":\"sk\",\"securitytoken\":\"token\"}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("access");
    }

    @Test
    void metadataDocumentWithoutCredentialObjectIsRejected() {
        assertThatThrownBy(() -> ObsCredentialProvider.parseCredentials("{\"other\":{}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credential");
    }

    @Test
    void metadataDocumentWithBlankValuesIsRejected() {
        assertThatThrownBy(() -> ObsCredentialProvider.parseCredentials(
                "{\"credential\":{\"access\":\"  \",\"secret\":\"sk\"}}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void malformedMetadataDocumentIsRejected() {
        assertThatThrownBy(() -> ObsCredentialProvider.parseCredentials("not json at all"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void emptyMetadataResponseIsRejected() {
        assertThatThrownBy(() -> ObsCredentialProvider.parseCredentials("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void nonOkMetadataStatusIsReportedWithoutLeakingTheDocument() {
        ObsStorageProperties properties = properties(ObsStorageProperties.CredentialMode.METADATA);
        ObsCredentialProvider provider = new ObsCredentialProvider(properties,
                (url, timeout) -> new ObsCredentialProvider.MetadataResponse(403, VALID_DOCUMENT));

        assertThatThrownBy(provider::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 403")
                .hasMessageNotContaining("temp-sk");
    }

    @Test
    void unreachableMetadataServiceIsReportedWithTheUrl() {
        ObsStorageProperties properties = properties(ObsStorageProperties.CredentialMode.METADATA);
        properties.setMetadataUrl("http://169.254.169.254/openstack/latest/securitykey");
        ObsCredentialProvider provider = new ObsCredentialProvider(properties, (url, timeout) -> {
            throw new IOException("connection refused");
        });

        assertThatThrownBy(provider::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("169.254.169.254")
                .hasMessageContaining("IOException");
    }

    @Test
    void metadataTimeoutIsReportedAsAnUnreachableService() {
        ObsStorageProperties properties = properties(ObsStorageProperties.CredentialMode.METADATA);
        ObsCredentialProvider provider = new ObsCredentialProvider(properties, (url, timeout) -> {
            throw new java.net.http.HttpTimeoutException("request timed out");
        });

        assertThatThrownBy(provider::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HttpTimeoutException");
    }

    @Test
    void interruptedMetadataFetchKeepsTheInterruptFlag() {
        ObsStorageProperties properties = properties(ObsStorageProperties.CredentialMode.METADATA);
        ObsCredentialProvider provider = new ObsCredentialProvider(properties, (url, timeout) -> {
            throw new InterruptedException("cancelled");
        });

        try {
            assertThatThrownBy(provider::current).isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void metadataCredentialsAreReusedWithinTheTtl() {
        ObsStorageProperties properties = properties(ObsStorageProperties.CredentialMode.METADATA);
        properties.setCredentialTtl(Duration.ofMinutes(50));
        AtomicInteger metadataCalls = new AtomicInteger();
        ObsCredentialProvider provider = new ObsCredentialProvider(properties, (url, timeout) -> {
            metadataCalls.incrementAndGet();
            return new ObsCredentialProvider.MetadataResponse(200, VALID_DOCUMENT);
        });

        provider.current();
        provider.current();
        provider.current();

        assertThat(metadataCalls).hasValue(1);
    }

    @Test
    void expiredCredentialsAreRefreshed() {
        ObsStorageProperties properties = properties(ObsStorageProperties.CredentialMode.METADATA);
        properties.setCredentialTtl(Duration.ZERO);
        AtomicInteger metadataCalls = new AtomicInteger();
        ObsCredentialProvider provider = new ObsCredentialProvider(properties, (url, timeout) -> {
            int call = metadataCalls.incrementAndGet();
            return new ObsCredentialProvider.MetadataResponse(200,
                    "{\"credential\":{\"access\":\"ak-" + call + "\",\"secret\":\"sk\",\"securitytoken\":\"t\"}}");
        });

        assertThat(provider.current().accessKey()).isEqualTo("ak-1");
        assertThat(provider.current().accessKey()).isEqualTo("ak-2");
        assertThat(metadataCalls).hasValue(2);
    }

    @Test
    void failedRefreshKeepsWorkingWithThePreviousCredentials() {
        ObsStorageProperties properties = properties(ObsStorageProperties.CredentialMode.METADATA);
        properties.setCredentialTtl(Duration.ZERO);
        List<Integer> attempts = new ArrayList<>();
        ObsCredentialProvider provider = new ObsCredentialProvider(properties, (url, timeout) -> {
            attempts.add(1);
            if (attempts.size() == 1) {
                return new ObsCredentialProvider.MetadataResponse(200, VALID_DOCUMENT);
            }
            throw new IOException("metadata service unavailable");
        });

        ObsCredentialProvider.Credentials first = provider.current();
        ObsCredentialProvider.Credentials afterFailure = provider.current();
        ObsCredentialProvider.Credentials whileSuppressed = provider.current();

        assertThat(afterFailure).isEqualTo(first);
        assertThat(whileSuppressed).isEqualTo(first);
        // The failed refresh is not retried on every single call.
        assertThat(attempts).hasSize(2);
    }

    @Test
    void credentialsNeverRenderSecrets() {
        ObsCredentialProvider.Credentials credentials =
                new ObsCredentialProvider.Credentials("ak", "super-secret", "the-token");

        assertThat(credentials.toString())
                .doesNotContain("super-secret")
                .doesNotContain("the-token")
                .contains("securityToken=present");
    }

    private ObsStorageProperties properties(ObsStorageProperties.CredentialMode mode) {
        ObsStorageProperties properties = new ObsStorageProperties();
        properties.setBucket("skillhub");
        properties.setEndpoint("https://obs.cn-north-4.myhuaweicloud.com");
        properties.setCredentialMode(mode);
        return properties;
    }
}
