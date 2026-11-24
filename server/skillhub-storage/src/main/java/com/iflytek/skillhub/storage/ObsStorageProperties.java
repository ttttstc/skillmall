package com.iflytek.skillhub.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration for the native Huawei Cloud OBS adapter selected by
 * {@code skillhub.storage.provider=obs}.
 *
 * <p>All values bind to environment names produced by Spring's relaxed binding, for example
 * {@code SKILLHUB_STORAGE_OBS_BUCKET}, {@code SKILLHUB_STORAGE_OBS_ENDPOINT},
 * {@code SKILLHUB_STORAGE_OBS_PUBLIC_ENDPOINT}, {@code SKILLHUB_STORAGE_OBS_PRESIGN_EXPIRY},
 * {@code SKILLHUB_STORAGE_OBS_CREDENTIAL_MODE}, {@code SKILLHUB_STORAGE_OBS_ACCESS_KEY},
 * {@code SKILLHUB_STORAGE_OBS_SECRET_KEY}, {@code SKILLHUB_STORAGE_OBS_METADATA_URL},
 * {@code SKILLHUB_STORAGE_OBS_METADATA_TIMEOUT} and
 * {@code SKILLHUB_STORAGE_OBS_CREDENTIAL_TTL}.
 *
 * <p>{@code publicEndpoint} is the browser-reachable host used for presigned URLs (empty means
 * "same as endpoint"). {@code endpoint} stays the in-cluster endpoint used for server-side
 * operations, which is the same split the S3 adapter exposes.
 */
@Component
@ConfigurationProperties(prefix = "skillhub.storage.obs")
public class ObsStorageProperties {
    /**
     * CCE/ECS metadata endpoint returning the OpenStack-style temporary security key document.
     * The response is {@code {"credential":{"access":..,"secret":..,"securitytoken":..}}}.
     */
    public static final String DEFAULT_METADATA_URL = "http://169.254.169.254/openstack/latest/securitykey";

    /** How OBS credentials are obtained. */
    public enum CredentialMode {
        /** Static keys when both are configured, otherwise the metadata service. */
        AUTO,
        /** Only the configured static access/secret key pair. */
        STATIC,
        /** Only short-lived credentials from the metadata service. */
        METADATA
    }

    private String bucket;
    private String endpoint;
    private String publicEndpoint;
    private Duration presignExpiry = Duration.ofMinutes(10);
    private CredentialMode credentialMode = CredentialMode.AUTO;
    private String accessKey;
    private String secretKey;
    private String metadataUrl = DEFAULT_METADATA_URL;
    private Duration metadataTimeout = Duration.ofSeconds(5);
    /** Refresh interval for temporary credentials, shorter than their ~1h lifetime. */
    private Duration credentialTtl = Duration.ofMinutes(50);
    private Duration connectionTimeout = Duration.ofSeconds(10);
    private Duration socketTimeout = Duration.ofSeconds(30);
    private int maxConnections = 100;
    private int maxErrorRetry = 2;

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getPublicEndpoint() { return publicEndpoint; }
    public void setPublicEndpoint(String publicEndpoint) { this.publicEndpoint = publicEndpoint; }
    public Duration getPresignExpiry() { return presignExpiry; }
    public void setPresignExpiry(Duration presignExpiry) { this.presignExpiry = presignExpiry; }
    public CredentialMode getCredentialMode() { return credentialMode; }
    public void setCredentialMode(CredentialMode credentialMode) { this.credentialMode = credentialMode; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getMetadataUrl() { return metadataUrl; }
    public void setMetadataUrl(String metadataUrl) { this.metadataUrl = metadataUrl; }
    public Duration getMetadataTimeout() { return metadataTimeout; }
    public void setMetadataTimeout(Duration metadataTimeout) { this.metadataTimeout = metadataTimeout; }
    public Duration getCredentialTtl() { return credentialTtl; }
    public void setCredentialTtl(Duration credentialTtl) { this.credentialTtl = credentialTtl; }
    public Duration getConnectionTimeout() { return connectionTimeout; }
    public void setConnectionTimeout(Duration connectionTimeout) { this.connectionTimeout = connectionTimeout; }
    public Duration getSocketTimeout() { return socketTimeout; }
    public void setSocketTimeout(Duration socketTimeout) { this.socketTimeout = socketTimeout; }
    public int getMaxConnections() { return maxConnections; }
    public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }
    public int getMaxErrorRetry() { return maxErrorRetry; }
    public void setMaxErrorRetry(int maxErrorRetry) { this.maxErrorRetry = maxErrorRetry; }

    /** Whether a complete static access/secret key pair is configured. */
    public boolean hasStaticCredentials() {
        return notBlank(accessKey) && notBlank(secretKey);
    }

    /**
     * Effective credential source: {@link CredentialMode#AUTO} resolves to static keys when both
     * are present and to the metadata service otherwise.
     */
    public CredentialMode resolveCredentialMode() {
        if (credentialMode != null && credentialMode != CredentialMode.AUTO) {
            return credentialMode;
        }
        return hasStaticCredentials() ? CredentialMode.STATIC : CredentialMode.METADATA;
    }

    /** Signing host for presigned URLs; falls back to the operation endpoint. */
    public String resolvePresignEndpoint() {
        return notBlank(publicEndpoint) ? publicEndpoint : endpoint;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
