package com.iflytek.skillhub.storage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Native Huawei Cloud OBS implementation of {@link ObjectStorageService}, selected with
 * {@code skillhub.storage.provider=obs}.
 *
 * <p><b>Why this is not the S3 adapter.</b> OBS does not speak AWS SigV4. Its request
 * authentication is an S3-V2 style {@code Authorization: OBS <AccessKeyId>:<Signature>} header over
 * HMAC-SHA1 (Content-MD5, Content-Type, Date, canonicalized OBS headers and canonicalized
 * resource). Presigned URLs are likewise OBS-shaped:
 * {@code ?AccessKeyId=..&Expires=<absolute unix seconds>&Signature=..}, plus
 * {@code &x-obs-security-token=..} whenever temporary credentials from the CCE/ECS metadata service
 * are in use. The AWS SDK's SigV4 presigner has no way to bind that security-token query parameter
 * into the signature, so the temporary-credential deployment that is recommended on CCE cannot be
 * served through {@code skillhub.storage.provider=s3}. Do not "simplify" this adapter back onto the
 * S3 client for that reason; see also {@code docs/object-storage.md} on the Lead side.
 *
 * <p><b>Credentials.</b> Static access/secret keys win when configured; otherwise short-lived
 * credentials are read from the metadata service and refreshed every {@code credentialTtl}
 * (default 50 minutes, i.e. before the roughly one hour expiry). A failed refresh keeps the previous
 * client working and only logs a warning - see {@link ObsCredentialProvider}.
 *
 * <p><b>Staging.</b> Writes are staged into a private temporary file before they reach OBS, exactly
 * like the S3 adapter. The OBS SDK signs the payload and retries, so it needs a body it can replay;
 * callers can pass non-resettable request streams. Staging also keeps a skill package out of the JVM
 * heap: the SDK streams the file from disk (and switches to multipart upload for large objects)
 * instead of being handed an in-memory buffer. Temporary files use the JDK's private temporary file
 * permissions (0600 on POSIX) and are deleted on both success and failure; deployments must budget
 * temporary disk for concurrent uploads.
 */
@Service
@ConditionalOnProperty(name = "skillhub.storage.provider", havingValue = "obs")
public class ObsStorageService implements ObjectStorageService {
    /** Content type bound into every presigned upload URL; uploaders must send the same value. */
    static final String PRESIGNED_UPLOAD_CONTENT_TYPE = "application/zip";

    private static final Logger log = LoggerFactory.getLogger(ObsStorageService.class);
    /** OBS accepts at most 1000 keys per multi-object delete request. */
    private static final int MAX_DELETE_BATCH = 1000;
    /** Bounds one usage scan so a runaway prefix cannot page forever. */
    private static final int MAX_LIST_PAGES = 100;
    private static final int MAX_LIST_PAGE_SIZE = 1000;

    private final ObsStorageProperties properties;
    private final ObsCredentialProvider credentialProvider;
    private final ObsApiFactory apiFactory;
    private final Object clientLock = new Object();

    private volatile ClientHandle clientHandle;

    @Autowired
    public ObsStorageService(ObsStorageProperties properties) {
        this(properties, new ObsCredentialProvider(properties), new SdkObsApiFactory(properties));
    }

    ObsStorageService(ObsStorageProperties properties, ObsCredentialProvider credentialProvider,
                      ObsApiFactory apiFactory) {
        this.properties = properties;
        this.credentialProvider = credentialProvider;
        this.apiFactory = apiFactory;
    }

    @PostConstruct
    void init() {
        requireConfigured(properties.getBucket(), "skillhub.storage.obs.bucket");
        requireConfigured(properties.getEndpoint(), "skillhub.storage.obs.endpoint");
        ObsStorageProperties.CredentialMode mode = credentialProvider.mode();
        log.info("Initializing native OBS storage for bucket '{}' (credential mode {}, presign host configured {})",
                properties.getBucket(), mode,
                properties.getPublicEndpoint() != null && !properties.getPublicEndpoint().isBlank());
        try {
            // Warm up so credential or endpoint problems show up at startup instead of on the first
            // user-facing download. A failure is not fatal: temporary credentials may simply not be
            // available yet while the instance is still starting.
            api();
        } catch (RuntimeException exception) {
            log.warn("OBS storage is not ready yet and will retry on first use: {}", exception.getMessage());
        }
    }

    @PreDestroy
    void close() {
        ClientHandle handle = clientHandle;
        clientHandle = null;
        if (handle == null) {
            return;
        }
        try {
            handle.api().close();
        } catch (RuntimeException exception) {
            log.warn("Failed to close the OBS client cleanly", exception);
        }
    }

    /**
     * Returns the API bound to the current credentials, rebuilding the client after a refresh.
     *
     * <p>When a credential refresh fails, {@link ObsCredentialProvider} returns the previous
     * credentials unchanged, so the already working client keeps being used instead of failing every
     * operation.
     */
    private ObsApi api() {
        ObsCredentialProvider.Credentials credentials = credentialProvider.current();
        ClientHandle current = clientHandle;
        if (current != null && current.credentials().equals(credentials)) {
            return current.api();
        }
        synchronized (clientLock) {
            current = clientHandle;
            if (current != null && current.credentials().equals(credentials)) {
                return current.api();
            }
            ObsApi refreshed = apiFactory.create(credentials);
            clientHandle = new ClientHandle(credentials, refreshed);
            if (current != null) {
                // Requests still in flight on the previous client may fail once it is closed; a
                // credential rotation happens at most once per credentialTtl, and callers retry.
                closeRetired(current.api());
            }
            log.info("Initialized OBS client with {} credentials",
                    credentials.hasSecurityToken() ? "temporary (security token present)" : "static");
            return refreshed;
        }
    }

    private void closeRetired(ObsApi api) {
        try {
            api.close();
        } catch (RuntimeException exception) {
            log.warn("Failed to close the superseded OBS client", exception);
        }
    }

    @Override
    public void putObject(String key, InputStream data, long size, String contentType) {
        Path stagedBody = null;
        try {
            stagedBody = stagePutObjectBody(data);
            if (Files.size(stagedBody) != size) {
                throw new IllegalArgumentException("Object size does not match the supplied stream");
            }
            api().putObject(key, stagedBody, size, contentType);
        } catch (IOException exception) {
            throw new StorageAccessException("putObject", key, exception);
        } catch (RuntimeException exception) {
            throw new StorageAccessException("putObject", key, exception);
        } finally {
            deleteStagedBody(stagedBody);
        }
    }

    private Path stagePutObjectBody(InputStream data) throws IOException {
        Path stagedBody = Files.createTempFile("skillhub-obs-upload-", ".tmp");
        try {
            // Write into the existing private file; replacing it can widen its permissions.
            try (var output = Files.newOutputStream(stagedBody)) {
                data.transferTo(output);
            }
            return stagedBody;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(stagedBody);
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    private void deleteStagedBody(Path stagedBody) {
        if (stagedBody == null) {
            return;
        }
        try {
            Files.deleteIfExists(stagedBody);
        } catch (IOException exception) {
            log.warn("Failed to clean up staged OBS upload body", exception);
        }
    }

    @Override
    public InputStream getObject(String key) {
        try {
            return api().getObject(key);
        } catch (RuntimeException exception) {
            throw new StorageAccessException("getObject", key, exception);
        }
    }

    @Override
    public void deleteObject(String key) {
        try {
            api().deleteObject(key);
        } catch (RuntimeException exception) {
            throw new StorageAccessException("deleteObject", key, exception);
        }
    }

    @Override
    public void deleteObjects(List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        try {
            for (int start = 0; start < keys.size(); start += MAX_DELETE_BATCH) {
                List<String> batch = keys.subList(start, Math.min(start + MAX_DELETE_BATCH, keys.size()));
                List<String> failed = api().deleteObjects(batch);
                // OBS answers with per-object results. Earlier successful deletes cannot be rolled
                // back, so report the failure and let callers retry idempotently instead of
                // pretending an HTTP 200 means every object is gone.
                if (!failed.isEmpty()) {
                    throw new IllegalStateException("OBS batch deletion reported " + failed.size() + " failures");
                }
            }
        } catch (RuntimeException exception) {
            throw new StorageAccessException("deleteObjects", String.join(",", keys), exception);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            return api().exists(key);
        } catch (RuntimeException exception) {
            throw new StorageAccessException("exists", key, exception);
        }
    }

    @Override
    public ObjectMetadata getMetadata(String key) {
        try {
            return api().getMetadata(key);
        } catch (RuntimeException exception) {
            throw new StorageAccessException("getMetadata", key, exception);
        }
    }

    @Override
    public Optional<StoragePrefixUsage> listPrefixUsage(String prefix, int maxObjects) {
        if (prefix == null || prefix.isBlank() || maxObjects < 1) {
            throw new IllegalArgumentException("A prefix and positive object limit are required");
        }
        try {
            long bytes = 0;
            long objects = 0;
            String marker = null;
            int pages = 0;
            while (objects < maxObjects && pages < MAX_LIST_PAGES) {
                int pageSize = (int) Math.min((long) MAX_LIST_PAGE_SIZE, maxObjects - objects);
                ObsApi.ObsObjectPage page = api().listObjects(prefix, marker, pageSize);
                pages++;
                for (ObsApi.ObsObjectSummary summary : page.objects()) {
                    bytes = Math.addExact(bytes, summary.size());
                    objects++;
                }
                if (!page.truncated()) {
                    return Optional.of(new StoragePrefixUsage(bytes, objects, true));
                }
                String nextMarker = page.nextMarker();
                if (nextMarker == null || nextMarker.isBlank() || nextMarker.equals(marker)) {
                    // OBS reported more data but gave no usable cursor: the scan is not complete.
                    return Optional.of(new StoragePrefixUsage(bytes, objects, false));
                }
                marker = nextMarker;
            }
            return Optional.of(new StoragePrefixUsage(bytes, objects, false));
        } catch (RuntimeException exception) {
            throw new StorageAccessException("listPrefixUsage", prefix, exception);
        }
    }

    @Override
    public String generatePresignedUrl(String key, Duration expiry, String downloadFilename) {
        Duration signatureDuration = expiry != null ? expiry : properties.getPresignExpiry();
        long expiresSeconds = toExpiresSeconds(signatureDuration);
        try {
            return api().presignGet(key, expiresSeconds, contentDisposition(downloadFilename));
        } catch (RuntimeException exception) {
            throw new StorageAccessException("generatePresignedUrl", key, exception);
        }
    }

    @Override
    public Optional<PresignedUpload> presignPut(String key, Duration expiry) {
        Duration signatureDuration = expiry != null ? expiry : properties.getPresignExpiry();
        long expiresSeconds = toExpiresSeconds(signatureDuration);
        try {
            // The signature is bound to this exact Content-Type; PresignedUpload.headers carries it
            // back so the uploader cannot accidentally send a different one (OBS would answer 403).
            return Optional.of(api().presignPut(key, expiresSeconds, PRESIGNED_UPLOAD_CONTENT_TYPE));
        } catch (RuntimeException exception) {
            throw new StorageAccessException("presignPut", key, exception);
        }
    }

    /**
     * OBS {@code Expires} is an absolute Unix timestamp in seconds, so it is derived by adding the
     * requested validity to "now" inside the SDK. Only the validity is converted here, which cannot
     * overflow for any realistic {@link Duration} (the whole point is that it is seconds, not a
     * seven day SigV4 cap).
     */
    private static long toExpiresSeconds(Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("A presigned URL validity is required");
        }
        long seconds = duration.getSeconds();
        if (seconds < 1) {
            throw new IllegalArgumentException("Presigned URL validity must be at least one second");
        }
        return seconds;
    }

    /**
     * Mirrors the S3 adapter's download semantics: an RFC 5987 {@code filename*} parameter so
     * non-ASCII names survive, expressed on OBS through {@code response-content-disposition}.
     */
    private static String contentDisposition(String downloadFilename) {
        if (downloadFilename == null || downloadFilename.isBlank()) {
            return "attachment";
        }
        return "attachment; filename*=UTF-8''"
                + URLEncoder.encode(downloadFilename, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void requireConfigured(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " is required when skillhub.storage.provider=obs");
        }
    }

    /** Credentials and the API object created for them; replaced atomically on refresh. */
    private record ClientHandle(ObsCredentialProvider.Credentials credentials, ObsApi api) {
    }
}
