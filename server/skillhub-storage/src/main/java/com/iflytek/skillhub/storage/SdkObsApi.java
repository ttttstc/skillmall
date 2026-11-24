package com.iflytek.skillhub.storage;

import com.obs.services.ObsClient;
import com.obs.services.exception.ObsException;
import com.obs.services.model.DeleteObjectsRequest;
import com.obs.services.model.DeleteObjectsResult;
import com.obs.services.model.HttpMethodEnum;
import com.obs.services.model.ListObjectsRequest;
import com.obs.services.model.ObjectListing;
import com.obs.services.model.ObsObject;
import com.obs.services.model.TemporarySignatureRequest;
import com.obs.services.model.TemporarySignatureResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ObsApi} backed by the official Huawei Cloud OBS SDK
 * ({@code com.huaweicloud:esdk-obs-java-bundle}). One instance owns one credential generation and
 * two SDK clients: one for server-side operations on the in-cluster endpoint, one for local
 * signing on the browser-reachable endpoint.
 *
 * <p>Protocol facts this class depends on (verified against esdk-obs-java-bundle 3.26.9 and the
 * OBS documentation, see the class comment of {@link ObsStorageService}):
 * <ul>
 *   <li>{@code createTemporarySignature} signs locally and never performs an HTTP call, so a
 *       successful call has no HTTP status. Only a present {@code signedUrl} reports success; the
 *       reference implementation crashed by comparing a {@code null} status against 300.</li>
 *   <li>With temporary credentials the SDK writes {@code x-obs-security-token} into the signed
 *       query string itself. Adding it again through {@code setQueryParams} yields a byte-identical
 *       URL (verified), but it is not needed and is therefore not duplicated here; the token is
 *       instead asserted on the produced URL.</li>
 *   <li>{@code Content-Type} is folded into the string to sign, so a presigned PUT must carry the
 *       very same value when it is actually sent.</li>
 * </ul>
 */
final class SdkObsApi implements ObsApi {
    private static final int NOT_FOUND = 404;
    private static final String RESPONSE_CONTENT_DISPOSITION = "response-content-disposition";

    private final ObsClient operationClient;
    private final ObsClient signingClient;
    private final String bucket;
    private final String securityToken;

    SdkObsApi(ObsClient operationClient, ObsClient signingClient, String bucket) {
        this(operationClient, signingClient, bucket, null);
    }

    SdkObsApi(ObsClient operationClient, ObsClient signingClient, String bucket, String securityToken) {
        this.operationClient = operationClient;
        this.signingClient = signingClient;
        this.bucket = bucket;
        this.securityToken = securityToken;
    }

    @Override
    public void putObject(String key, Path body, long size, String contentType) {
        // The SDK reads the file itself (and switches to multipart upload for large objects)
        // instead of buffering the package in the JVM heap. Content-Length is stated explicitly:
        // the staged file was just measured, so the request does not depend on the SDK deriving it.
        var metadata = new com.obs.services.model.ObjectMetadata();
        metadata.setContentType(contentType);
        metadata.setContentLength(size);
        operationClient.putObject(bucket, key, body.toFile(), metadata);
    }

    @Override
    public InputStream getObject(String key) {
        ObsObject object = operationClient.getObject(bucket, key);
        return object.getObjectContent();
    }

    @Override
    public boolean exists(String key) {
        try {
            operationClient.getObjectMetadata(bucket, key);
            return true;
        } catch (ObsException exception) {
            // Only "not found" means missing; 403 must surface so callers do not overwrite or
            // re-create an object they are not allowed to read.
            if (exception.getResponseCode() == NOT_FOUND) {
                return false;
            }
            throw exception;
        }
    }

    @Override
    public ObjectMetadata getMetadata(String key) {
        var metadata = operationClient.getObjectMetadata(bucket, key);
        Long contentLength = metadata.getContentLength();
        if (contentLength == null) {
            throw new IllegalStateException("OBS returned object metadata without a content length for " + key);
        }
        return new ObjectMetadata(contentLength, metadata.getContentType(),
                metadata.getLastModified() == null ? null : metadata.getLastModified().toInstant());
    }

    @Override
    public void deleteObject(String key) {
        operationClient.deleteObject(bucket, key);
    }

    @Override
    public List<String> deleteObjects(List<String> keys) {
        DeleteObjectsRequest request = new DeleteObjectsRequest(bucket);
        // Quiet mode would report nothing at all; the verbose form lists every deleted key and
        // every per-object failure, which is what the caller needs to detect partial failure.
        request.setQuiet(false);
        for (String key : keys) {
            request.addKeyAndVersion(key);
        }
        DeleteObjectsResult result = operationClient.deleteObjects(request);

        List<String> failedKeys = new ArrayList<>();
        List<DeleteObjectsResult.ErrorResult> errors = result.getErrorResults();
        if (errors != null) {
            for (DeleteObjectsResult.ErrorResult error : errors) {
                if (error.getObjectKey() != null && !failedKeys.contains(error.getObjectKey())) {
                    failedKeys.add(error.getObjectKey());
                }
            }
        }
        List<DeleteObjectsResult.DeleteObjectResult> deleted = result.getDeletedObjectResults();
        if (deleted != null && !deleted.isEmpty()) {
            // The response reports deletions per object, so anything requested but neither
            // deleted nor failed is a silent failure and must be reported instead of trusted.
            for (String key : keys) {
                boolean reported = failedKeys.contains(key)
                        || deleted.stream().anyMatch(deletedResult -> key.equals(deletedResult.getObjectKey()));
                if (!reported) {
                    failedKeys.add(key);
                }
            }
        }
        return failedKeys;
    }

    @Override
    public ObsObjectPage listObjects(String prefix, String marker, int maxKeys) {
        ListObjectsRequest request = new ListObjectsRequest(bucket);
        request.setPrefix(prefix);
        request.setMaxKeys(maxKeys);
        if (marker != null && !marker.isBlank()) {
            request.setMarker(marker);
        }
        ObjectListing listing = operationClient.listObjects(request);
        List<ObsObjectSummary> summaries = new ArrayList<>();
        List<ObsObject> objects = listing.getObjects();
        if (objects != null) {
            for (ObsObject object : objects) {
                var metadata = object.getMetadata();
                Long size = metadata == null ? null : metadata.getContentLength();
                java.util.Date lastModified = metadata == null ? null : metadata.getLastModified();
                summaries.add(new ObsObjectSummary(object.getObjectKey(), size == null ? 0L : size,
                        lastModified == null ? null : lastModified.toInstant()));
            }
        }
        return new ObsObjectPage(summaries, listing.getNextMarker(), listing.isTruncated());
    }

    @Override
    public String presignGet(String key, long expiresSeconds, String contentDisposition) {
        TemporarySignatureRequest request = new TemporarySignatureRequest(HttpMethodEnum.GET, expiresSeconds);
        request.setBucketName(bucket);
        request.setObjectKey(key);
        if (contentDisposition != null && !contentDisposition.isBlank()) {
            // OBS signs query parameters into the URL; the download filename is expressed through
            // response-content-disposition, which is what browsers honour for a presigned GET.
            Map<String, Object> queryParams = new LinkedHashMap<>();
            queryParams.put(RESPONSE_CONTENT_DISPOSITION, contentDisposition);
            request.setQueryParams(queryParams);
        }
        return requireSignedUrl(signingClient.createTemporarySignature(request), "GET", key);
    }

    @Override
    public PresignedUpload presignPut(String key, long expiresSeconds, String contentType) {
        TemporarySignatureRequest request = new TemporarySignatureRequest(HttpMethodEnum.PUT, expiresSeconds);
        request.setBucketName(bucket);
        request.setObjectKey(key);
        if (contentType != null && !contentType.isBlank()) {
            // OBS folds Content-Type into the string to sign: signing without it (or with a
            // different value than the uploader sends) produces a URL that looks valid but is
            // rejected with 403 on PUT.
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", contentType);
            request.setHeaders(headers);
        }
        TemporarySignatureResponse response = signingClient.createTemporarySignature(request);
        String url = requireSignedUrl(response, "PUT", key);

        Map<String, String> requiredHeaders = new LinkedHashMap<>();
        Map<String, String> signedHeaders = response.getActualSignedRequestHeaders();
        if (signedHeaders != null) {
            signedHeaders.forEach((name, value) -> {
                if (!"host".equalsIgnoreCase(name) && value != null) {
                    requiredHeaders.put(name, value);
                }
            });
        }
        return new PresignedUpload(url, Map.copyOf(requiredHeaders));
    }

    @Override
    public void close() {
        IOException failure = null;
        failure = closeQuietly(operationClient, failure);
        if (signingClient != operationClient) {
            failure = closeQuietly(signingClient, failure);
        }
        if (failure != null) {
            throw new StorageAccessException("close", bucket, failure);
        }
    }

    private static IOException closeQuietly(ObsClient client, IOException failure) {
        try {
            client.close();
            return failure;
        } catch (IOException closeFailure) {
            if (failure == null) {
                return closeFailure;
            }
            failure.addSuppressed(closeFailure);
            return failure;
        }
    }

    /**
     * Signing happens locally, so there is no status code to inspect: an absent URL is the only
     * signal that signing failed.
     *
     * <p>When temporary credentials are in use the security token must be part of the signed query
     * ({@code x-obs-security-token}); the SDK adds it for a client built with a token, and a URL
     * without it would be rejected by OBS. Verifying here turns a silent 403 into a clear failure.
     */
    private String requireSignedUrl(TemporarySignatureResponse response, String method, String key) {
        String url = response == null ? null : response.getSignedUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("OBS returned no presigned " + method + " URL for " + key);
        }
        if (securityToken != null && !securityToken.isBlank()
                && !url.contains(ObsCredentialProvider.SECURITY_TOKEN_QUERY_PARAMETER + "=")) {
            throw new IllegalStateException("OBS presigned " + method + " URL is missing the "
                    + ObsCredentialProvider.SECURITY_TOKEN_QUERY_PARAMETER + " query parameter required "
                    + "by temporary credentials");
        }
        return url;
    }
}
