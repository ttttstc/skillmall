package com.iflytek.skillhub.storage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Real OBS round trip shared by the opt-in contract test. Never prints signed URLs.
 *
 * <p>This probe is deliberately separate from {@link StorageContractProbe}: that one asserts SigV4
 * query parameters ({@code X-Amz-Signature}, {@code X-Amz-SignedHeaders}) and therefore only
 * applies to S3-compatible backends. OBS uses {@code AccessKeyId}/{@code Expires}/{@code Signature}
 * and has no SigV4 at all.
 */
final class ObsStorageLiveProbe {
    private final ObjectStorageService storage;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    ObsStorageLiveProbe(ObjectStorageService storage) {
        this.storage = storage;
    }

    void verify() throws Exception {
        String prefix = "skillhub-obs-diagnostics/" + UUID.randomUUID() + "/";
        System.out.println("Diagnostic object prefix: " + prefix);
        List<String> keys = new ArrayList<>();
        Exception failure = null;
        try {
            byte[] content = new byte[256 * 1024 + 1];
            new Random(937).nextBytes(content);
            String key = prefix + "中文 空格+%?#/package.zip";
            keys.add(key);
            var sourceFile = java.nio.file.Files.createTempFile("skillhub-obs-contract-", ".zip");
            try {
                java.nio.file.Files.write(sourceFile, content);
                try (var source = java.nio.file.Files.newInputStream(sourceFile)) {
                    storage.putObject(key, source, content.length, "application/zip");
                }
            } finally {
                java.nio.file.Files.deleteIfExists(sourceFile);
            }
            require(storage.exists(key), "uploaded object missing");
            try (var input = storage.getObject(key)) {
                require(Arrays.equals(input.readAllBytes(), content), "object bytes differ");
            }
            ObjectMetadata metadata = storage.getMetadata(key);
            require(metadata.size() == content.length, "object size differs");
            require("application/zip".equals(metadata.contentType()), "content type differs");
            require(metadata.lastModified() != null, "last modified is missing");

            String signedGet = storage.generatePresignedUrl(key, Duration.ofMinutes(1), "中文 package.zip");
            require(isObsPresignedUrl(signedGet), "presigned URL is not in OBS format");
            var download = send(HttpRequest.newBuilder(URI.create(signedGet)).GET());
            require(download.statusCode() == 200 && Arrays.equals(download.body(), content), "signed GET failed");
            require(download.headers().firstValue("Content-Disposition").orElse("").contains("filename*="),
                    "download filename missing");

            var tampered = send(HttpRequest.newBuilder(tamperSignature(signedGet)).GET());
            require(tampered.statusCode() == 403, "tampered signature was not rejected");
            var anonymous = send(HttpRequest.newBuilder(URI.create(signedGet.split("\\?")[0])).GET());
            require(anonymous.statusCode() == 403, "anonymous private object read was not rejected");
            String expiringGet = storage.generatePresignedUrl(key, Duration.ofSeconds(1), null);
            TimeUnit.SECONDS.sleep(3);
            require(send(HttpRequest.newBuilder(URI.create(expiringGet)).GET()).statusCode() == 403,
                    "expired signature was not rejected");

            String putKey = prefix + "staging.zip";
            keys.add(putKey);
            PresignedUpload upload = storage.presignPut(putKey, Duration.ofMinutes(1)).orElseThrow();
            require(upload.headers().getOrDefault("Content-Type", "").equals("application/zip"),
                    "presigned PUT does not require the signed content type");
            // Content-Type is part of the string to sign: without the header the PUT must fail and
            // must not create the object.
            var missingHeader = send(HttpRequest.newBuilder(URI.create(upload.url()))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(content)));
            require(missingHeader.statusCode() == 400 || missingHeader.statusCode() == 403,
                    "PUT without the signed Content-Type was accepted");
            require(!storage.exists(putKey), "rejected PUT wrote an object");
            System.out.println("Missing Content-Type PUT rejected with HTTP " + missingHeader.statusCode());

            var badPutRequest = HttpRequest.newBuilder(tamperSignature(upload.url()));
            upload.headers().forEach(badPutRequest::header);
            require(send(badPutRequest.PUT(HttpRequest.BodyPublishers.ofByteArray(content))).statusCode() == 403,
                    "tampered PUT signature was not rejected");
            require(!storage.exists(putKey), "tampered PUT wrote an object");

            var putRequest = HttpRequest.newBuilder(URI.create(upload.url()));
            upload.headers().forEach(putRequest::header);
            var uploaded = send(putRequest.PUT(HttpRequest.BodyPublishers.ofByteArray(content)));
            require(uploaded.statusCode() >= 200 && uploaded.statusCode() < 300, "signed PUT failed");
            try (var input = storage.getObject(putKey)) {
                require(Arrays.equals(input.readAllBytes(), content), "signed PUT bytes differ");
            }
            require("application/zip".equals(storage.getMetadata(putKey).contentType()),
                    "signed PUT content type differs");

            var usage = storage.listPrefixUsage(prefix, 10).orElseThrow();
            require(usage.complete() && usage.objectCount() == 2 && usage.bytes() == content.length * 2L,
                    "prefix usage differs");
            var limited = storage.listPrefixUsage(prefix, 1).orElseThrow();
            require(!limited.complete() && limited.objectCount() == 1, "truncated usage reported complete");

            storage.deleteObject(putKey);
            require(!storage.exists(putKey), "single delete failed");
            storage.deleteObjects(keys);
            require(!storage.exists(key), "batch delete failed");
        } catch (Exception exception) {
            failure = exception;
            throw exception;
        } finally {
            // Delete only keys created by this invocation, even when a write timed out.
            Exception cleanupFailure = null;
            for (String key : keys) {
                try {
                    storage.deleteObject(key);
                } catch (Exception exception) {
                    if (cleanupFailure == null) {
                        cleanupFailure = new IOException("Diagnostic cleanup failed for " + prefix);
                    }
                    cleanupFailure.addSuppressed(exception);
                }
            }
            if (cleanupFailure != null) {
                if (failure != null) {
                    failure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    /** OBS presigned URLs carry AccessKeyId/Expires/Signature and never SigV4 parameters. */
    static boolean isObsPresignedUrl(String url) {
        return url != null
                && url.contains("AccessKeyId=")
                && url.contains("Expires=")
                && url.contains("Signature=")
                && !url.contains("X-Amz-Signature=");
    }

    /** Changes one character of the {@code Signature=} value while keeping the URL well formed. */
    static URI tamperSignature(String signedUrl) {
        String marker = "Signature=";
        int start = signedUrl.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("Expected an OBS query signature");
        }
        int index = start + marker.length();
        String tampered = (signedUrl.charAt(index) == 'A' ? "B" : "A");
        return URI.create(signedUrl.substring(0, index) + tampered + signedUrl.substring(index + 1));
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
