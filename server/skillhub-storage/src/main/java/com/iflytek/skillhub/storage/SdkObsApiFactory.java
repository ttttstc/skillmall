package com.iflytek.skillhub.storage;

import com.obs.services.BasicObsCredentialsProvider;
import com.obs.services.IObsCredentialsProvider;
import com.obs.services.ObsClient;
import com.obs.services.ObsConfiguration;

import java.time.Duration;

/**
 * Builds real OBS SDK clients.
 *
 * <p>Addressing is virtual-hosted only: OBS documents that any request carrying a bucket name must
 * put it in front of the service domain ({@code <bucket>.obs.<region>.myhuaweicloud.com}). Path
 * style addressing is therefore not offered as a configuration option, unlike the S3 adapter.
 *
 * <p>Two clients are created when {@code publicEndpoint} differs from {@code endpoint}: server-side
 * operations use the in-cluster endpoint while presigned URLs are signed against the
 * browser-reachable host. Signing is a local HMAC operation, so the signing client never sends
 * traffic, but the host it is built with ends up in the URL.
 */
final class SdkObsApiFactory implements ObsApiFactory {
    private static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_SOCKET_TIMEOUT = Duration.ofSeconds(30);

    private final ObsStorageProperties properties;

    SdkObsApiFactory(ObsStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public ObsApi create(ObsCredentialProvider.Credentials credentials) {
        ObsClient operationClient = buildClient(properties.getEndpoint(), credentials);
        String presignEndpoint = properties.resolvePresignEndpoint();
        boolean sharedClient = presignEndpoint == null
                || presignEndpoint.isBlank()
                || presignEndpoint.equals(properties.getEndpoint());
        ObsClient signingClient = sharedClient ? operationClient : buildClient(presignEndpoint, credentials);
        return new SdkObsApi(operationClient, signingClient, properties.getBucket(), credentials.securityToken());
    }

    private ObsClient buildClient(String endpoint, ObsCredentialProvider.Credentials credentials) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException("skillhub.storage.obs.endpoint is required");
        }
        ObsConfiguration configuration = new ObsConfiguration();
        configuration.setEndPoint(endpoint);
        configuration.setPathStyle(false);
        configuration.setConnectionTimeout(toTimeoutMillis(properties.getConnectionTimeout(), DEFAULT_CONNECTION_TIMEOUT));
        configuration.setSocketTimeout(toTimeoutMillis(properties.getSocketTimeout(), DEFAULT_SOCKET_TIMEOUT));
        configuration.setMaxConnections(properties.getMaxConnections());
        configuration.setMaxErrorRetry(properties.getMaxErrorRetry());
        return new ObsClient(credentialsProvider(credentials), configuration);
    }

    private static IObsCredentialsProvider credentialsProvider(ObsCredentialProvider.Credentials credentials) {
        if (credentials.hasSecurityToken()) {
            // Passing the token through the credentials provider makes the SDK attach it as a
            // request header and as the x-obs-security-token query parameter on presigned URLs.
            return new BasicObsCredentialsProvider(
                    credentials.accessKey(), credentials.secretKey(), credentials.securityToken());
        }
        return new BasicObsCredentialsProvider(credentials.accessKey(), credentials.secretKey());
    }

    private static int toTimeoutMillis(Duration value, Duration fallback) {
        Duration effective = value != null && !value.isNegative() && !value.isZero() ? value : fallback;
        long millis = effective.toMillis();
        return millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) millis;
    }
}
