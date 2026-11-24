package com.iflytek.skillhub.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Narrow seam between {@link ObsStorageService} and the Huawei Cloud OBS SDK.
 *
 * <p>Everything the service needs from OBS is expressed here, which keeps the adapter free of
 * SDK types and lets the behaviour of the service (staging, credential refresh, pagination,
 * presigning semantics, error mapping) be tested without a network or a live bucket.
 *
 * <p>One instance is bound to one set of credentials: the service rebuilds the API object after a
 * credential refresh. Implementations must be thread-safe; {@link #close()} releases the
 * underlying SDK clients.
 */
interface ObsApi extends AutoCloseable {
    /**
     * Stores the staged file under {@code key} with an explicit content type.
     *
     * <p>{@code size} is the length already validated against the staged file, so the implementation
     * can state {@code Content-Length} instead of relying on the SDK deriving it.
     */
    void putObject(String key, Path body, long size, String contentType);

    /** Streams the object; the caller owns the returned stream. */
    InputStream getObject(String key);

    /** Whether the object exists; authorization failures must not be reported as "missing". */
    boolean exists(String key);

    ObjectMetadata getMetadata(String key);

    void deleteObject(String key);

    /**
     * Deletes up to a batch of keys.
     *
     * @return keys OBS reported as failed; empty when every requested object was deleted. A
     *         request-level failure is thrown instead of being reported as partial success.
     */
    List<String> deleteObjects(List<String> keys);

    /** One page of a marker-paginated listing. */
    ObsObjectPage listObjects(String prefix, String marker, int maxKeys);

    /** Presigned GET URL; {@code contentDisposition} becomes {@code response-content-disposition}. */
    String presignGet(String key, long expiresSeconds, String contentDisposition);

    /** Presigned PUT bound to {@code contentType}; returned headers must be sent with the PUT. */
    PresignedUpload presignPut(String key, long expiresSeconds, String contentType);

    @Override
    void close();

    /** One listing page. {@code nextMarker} is null when OBS reported no further page. */
    record ObsObjectPage(List<ObsObjectSummary> objects, String nextMarker, boolean truncated) {
    }

    /** One listed object. */
    record ObsObjectSummary(String key, long size, Instant lastModified) {
    }
}
