package com.iflytek.skillhub.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObsStorageServiceTest {

    @Test
    void putObjectStagesAReplayableBodyAndForwardsTheContentType() {
        FakeObsApi api = new FakeObsApi();
        ObsStorageService service = service(staticProperties(), api);
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

        service.putObject("packages/demo.zip", new ByteArrayInputStream(content), content.length, "application/zip");

        assertThat(api.putKeys).containsExactly("packages/demo.zip");
        assertThat(api.putBodies.get("packages/demo.zip")).isEqualTo(content);
        assertThat(api.putSizes).containsEntry("packages/demo.zip", (long) content.length);
        assertThat(api.putContentTypes).containsEntry("packages/demo.zip", "application/zip");
        // The staged temporary body must not survive the upload.
        assertThat(api.lastStagedBody).doesNotExist();
    }

    @Test
    void putObjectReadsTheStreamOnceIntoAPrivateTemporaryFile() {
        FakeObsApi api = new FakeObsApi();
        ObsStorageService service = service(staticProperties(), api);
        byte[] content = new byte[512 * 1024 + 1];
        new java.util.Random(937).nextBytes(content);

        service.putObject("packages/large.zip", new ByteArrayInputStream(content), content.length, "application/zip");

        assertThat(api.lastStagedBody).isNotNull();
        assertThat(api.putBodies.get("packages/large.zip")).isEqualTo(content);
    }

    @Test
    void putObjectRejectsASizeMismatchBeforeCallingObs() {
        FakeObsApi api = new FakeObsApi();
        ObsStorageService service = service(staticProperties(), api);

        assertThatThrownBy(() -> service.putObject("packages/demo.zip",
                new ByteArrayInputStream(new byte[]{42}), 2, "application/zip"))
                .isInstanceOf(StorageAccessException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .extracting("operation").isEqualTo("putObject");
        assertThat(api.putKeys).isEmpty();
    }

    @Test
    void putObjectMapsProviderFailuresAndRemovesTheStagedBody() {
        FakeObsApi api = new FakeObsApi();
        RuntimeException failure = new IllegalStateException("OBS unavailable");
        api.failure = failure;
        ObsStorageService service = service(staticProperties(), api);
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.putObject("packages/demo.zip",
                new ByteArrayInputStream(content), content.length, "application/zip"))
                .isInstanceOf(StorageAccessException.class)
                .hasCause(failure);
        assertThat(api.lastStagedBody).doesNotExist();
    }

    @Test
    void getObjectStreamsTheStoredBytes() throws IOException {
        FakeObsApi api = new FakeObsApi();
        api.objects.put("packages/demo.zip", "hello".getBytes(StandardCharsets.UTF_8));
        ObsStorageService service = service(staticProperties(), api);

        try (InputStream input = service.getObject("packages/demo.zip")) {
            assertThat(input.readAllBytes()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void getObjectMapsProviderFailures() {
        FakeObsApi api = new FakeObsApi();
        api.failure = new IllegalStateException("boom");
        ObsStorageService service = service(staticProperties(), api);

        assertThatThrownBy(() -> service.getObject("packages/demo.zip"))
                .isInstanceOf(StorageAccessException.class)
                .extracting("operation").isEqualTo("getObject");
    }

    @Test
    void deleteObjectForwardsTheKey() {
        FakeObsApi api = new FakeObsApi();
        ObsStorageService service = service(staticProperties(), api);

        service.deleteObject("packages/demo.zip");

        assertThat(api.deletedKeys).containsExactly("packages/demo.zip");
    }

    @Test
    void deleteObjectMapsProviderFailures() {
        FakeObsApi api = new FakeObsApi();
        api.failure = new IllegalStateException("boom");
        ObsStorageService service = service(staticProperties(), api);

        assertThatThrownBy(() -> service.deleteObject("packages/demo.zip"))
                .isInstanceOf(StorageAccessException.class)
                .extracting("operation").isEqualTo("deleteObject");
    }

    @Test
    void deleteObjectsPartitionsRequestsAtTheObsLimit() {
        FakeObsApi api = new FakeObsApi();
        ObsStorageService service = service(staticProperties(), api);
        List<String> keys = IntStream.range(0, 1001).mapToObj(index -> "packages/" + index).toList();

        service.deleteObjects(keys);

        assertThat(api.deleteBatchSizes).containsExactly(1000, 1);
        assertThat(api.deletedKeys).containsExactlyElementsOf(keys);
    }

    @Test
    void deleteObjectsRejectsPartialFailureReportedWithHttp200() {
        FakeObsApi api = new FakeObsApi();
        api.failedDeletes.add("private/denied.zip");
        ObsStorageService service = service(staticProperties(), api);

        assertThatThrownBy(() -> service.deleteObjects(List.of("private/denied.zip")))
                .isInstanceOf(StorageAccessException.class)
                .extracting("operation").isEqualTo("deleteObjects");
        assertThat(service.exists("private/denied.zip")).isFalse();
    }

    @Test
    void deleteObjectsSkipsTheProviderWhenNoKeysAreGiven() {
        FakeObsApi api = new FakeObsApi();
        ObsStorageService service = service(staticProperties(), api);

        service.deleteObjects(List.of());

        assertThat(api.deleteBatchSizes).isEmpty();
    }

    @Test
    void existsAndMetadataAreDelegated() {
        FakeObsApi api = new FakeObsApi();
        api.objects.put("packages/demo.zip", "hello".getBytes(StandardCharsets.UTF_8));
        api.contentTypes.put("packages/demo.zip", "application/zip");
        ObsStorageService service = service(staticProperties(), api);

        assertThat(service.exists("packages/demo.zip")).isTrue();
        assertThat(service.exists("packages/missing.zip")).isFalse();
        ObjectMetadata metadata = service.getMetadata("packages/demo.zip");
        assertThat(metadata.size()).isEqualTo(5);
        assertThat(metadata.contentType()).isEqualTo("application/zip");
    }

    @Test
    void authorizationFailuresAreNotReportedAsMissingObjects() {
        FakeObsApi api = new FakeObsApi();
        api.failure = new IllegalStateException("403 Forbidden");
        ObsStorageService service = service(staticProperties(), api);

        assertThatThrownBy(() -> service.exists("private/demo.zip"))
                .isInstanceOf(StorageAccessException.class)
                .extracting("operation").isEqualTo("exists");
    }

    @Test
    void prefixUsageFollowsMarkersAndSumsEveryPage() {
        FakeObsApi api = new FakeObsApi();
        api.listPages.add(new ObsApi.ObsObjectPage(
                List.of(new ObsApi.ObsObjectSummary("github-sync/staging/a.zip", 30L, Instant.EPOCH)), "page-2", true));
        api.listPages.add(new ObsApi.ObsObjectPage(
                List.of(new ObsApi.ObsObjectSummary("github-sync/staging/b.zip", 50L, Instant.EPOCH)), null, false));
        ObsStorageService service = service(staticProperties(), api);

        assertThat(service.listPrefixUsage("github-sync/staging/", 10_000))
                .contains(new StoragePrefixUsage(80, 2, true));
        assertThat(api.listMarkers).containsExactly(null, "page-2");
        assertThat(api.listPrefixes).containsOnly("github-sync/staging/");
    }

    @Test
    void prefixUsageReportsIncompleteWhenTheObjectBudgetIsReached() {
        FakeObsApi api = new FakeObsApi();
        api.listPages.add(new ObsApi.ObsObjectPage(
                List.of(new ObsApi.ObsObjectSummary("github-sync/staging/a.zip", 30L, Instant.EPOCH)), "page-2", true));
        ObsStorageService service = service(staticProperties(), api);

        assertThat(service.listPrefixUsage("github-sync/staging/", 1))
                .contains(new StoragePrefixUsage(30, 1, false));
        assertThat(api.listMaxKeys).containsExactly(1);
    }

    @Test
    void prefixUsageDoesNotClaimCompletionWhenObsReturnsNoMarker() {
        FakeObsApi api = new FakeObsApi();
        api.listPages.add(new ObsApi.ObsObjectPage(
                List.of(new ObsApi.ObsObjectSummary("github-sync/staging/a.zip", 7L, Instant.EPOCH)), null, true));
        ObsStorageService service = service(staticProperties(), api);

        assertThat(service.listPrefixUsage("github-sync/staging/", 10))
                .contains(new StoragePrefixUsage(7, 1, false));
    }

    @Test
    void prefixUsageRejectsInvalidArguments() {
        ObsStorageService service = service(staticProperties(), new FakeObsApi());

        assertThatThrownBy(() -> service.listPrefixUsage("", 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listPrefixUsage("prefix/", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void presignedGetCarriesAnRfc5987DownloadFilename() {
        FakeObsApi api = new FakeObsApi();
        ObsStorageService service = service(staticProperties(), api);

        service.generatePresignedUrl("packages/中文 package.zip", Duration.ofMinutes(5), "中文 package.zip");

        assertThat(api.presignedGetDispositions).containsExactly(
                "attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87%20package.zip");
        assertThat(api.presignedGetExpiries).containsExactly(300L);
    }

    @Test
    void presignedGetWithoutAFilenameStillForcesAnAttachment() {
        FakeObsApi api = new FakeObsApi();
        ObsStorageService service = service(staticProperties(), api);

        service.generatePresignedUrl("packages/demo.zip", Duration.ofMinutes(10), " ");

        assertThat(api.presignedGetDispositions).containsExactly("attachment");
    }

    @Test
    void presignedGetFallsBackToTheConfiguredExpiry() {
        FakeObsApi api = new FakeObsApi();
        ObsStorageProperties properties = staticProperties();
        properties.setPresignExpiry(Duration.ofMinutes(10));
        ObsStorageService service = service(properties, api);

        service.generatePresignedUrl("packages/demo.zip", null, null);

        assertThat(api.presignedGetExpiries).containsExactly(600L);
    }

    @Test
    void presignedExpiryMustBeAtLeastOneSecond() {
        ObsStorageService service = service(staticProperties(), new FakeObsApi());

        // Invalid caller input stays an IllegalArgumentException, like the S3 adapter's argument
        // checks; it is not an object-storage access failure.
        assertThatThrownBy(() -> service.generatePresignedUrl("packages/demo.zip", Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one second");
        assertThatThrownBy(() -> service.presignPut("staging/demo.zip", Duration.ofMillis(-5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void presignPutBindsTheZipContentTypeAndReturnsTheSignedHeaders() {
        FakeObsApi api = new FakeObsApi();
        ObsStorageService service = service(staticProperties(), api);

        PresignedUpload upload = service.presignPut("staging/demo.zip", Duration.ofMinutes(2)).orElseThrow();

        assertThat(api.presignedPutContentTypes).containsExactly(ObsStorageService.PRESIGNED_UPLOAD_CONTENT_TYPE);
        assertThat(api.presignedPutExpiries).containsExactly(120L);
        assertThat(upload.url()).isEqualTo("https://obs.example.test/skillhub/staging/demo.zip");
        assertThat(upload.headers()).containsEntry("Content-Type", "application/zip");
    }

    @Test
    void presignPutMapsProviderFailures() {
        FakeObsApi api = new FakeObsApi();
        api.failure = new IllegalStateException("signing failed");
        ObsStorageService service = service(staticProperties(), api);

        assertThatThrownBy(() -> service.presignPut("staging/demo.zip", Duration.ofMinutes(2)))
                .isInstanceOf(StorageAccessException.class)
                .extracting("operation").isEqualTo("presignPut");
    }

    @Test
    void temporaryCredentialsRotateTheClientAndCloseTheSupersededOne() {
        AtomicInteger fetches = new AtomicInteger();
        ObsStorageProperties properties = metadataProperties();
        ObsCredentialProvider credentials = new ObsCredentialProvider(properties, (url, timeout) -> {
            int fetch = fetches.incrementAndGet();
            return new ObsCredentialProvider.MetadataResponse(200, metadataDocument("ak-" + fetch));
        });
        List<FakeObsApi> created = new ArrayList<>();
        ObsStorageService service = new ObsStorageService(properties, credentials, clientCredentials -> {
            FakeObsApi api = new FakeObsApi();
            created.add(api);
            return api;
        });

        service.exists("packages/a.zip");
        service.exists("packages/a.zip");

        assertThat(created).hasSize(2);
        assertThat(created.get(0).closed).isTrue();
        assertThat(created.get(1).closed).isFalse();
    }

    @Test
    void failedCredentialRefreshKeepsTheWorkingClient() {
        AtomicInteger fetches = new AtomicInteger();
        ObsStorageProperties properties = metadataProperties();
        ObsCredentialProvider credentials = new ObsCredentialProvider(properties, (url, timeout) -> {
            if (fetches.incrementAndGet() == 1) {
                return new ObsCredentialProvider.MetadataResponse(200, metadataDocument("ak-1"));
            }
            throw new HttpTimeoutException("metadata service timed out");
        });
        List<FakeObsApi> created = new ArrayList<>();
        ObsStorageService service = new ObsStorageService(properties, credentials, clientCredentials -> {
            FakeObsApi api = new FakeObsApi();
            created.add(api);
            return api;
        });

        assertThat(service.exists("packages/a.zip")).isFalse();
        assertThat(service.exists("packages/a.zip")).isFalse();

        assertThat(created).hasSize(1);
        assertThat(created.get(0).closed).isFalse();
    }

    @Test
    void initRequiresBucketAndEndpoint() {
        ObsStorageProperties missingBucket = staticProperties();
        missingBucket.setBucket(null);
        assertThatThrownBy(() -> service(missingBucket, new FakeObsApi()).init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skillhub.storage.obs.bucket");

        ObsStorageProperties missingEndpoint = staticProperties();
        missingEndpoint.setEndpoint(" ");
        assertThatThrownBy(() -> service(missingEndpoint, new FakeObsApi()).init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skillhub.storage.obs.endpoint");
    }

    @Test
    void initSurvivesAnUnavailableMetadataServiceAndFailsOnFirstUse() {
        ObsStorageProperties properties = metadataProperties();
        ObsCredentialProvider credentials = new ObsCredentialProvider(properties, (url, timeout) -> {
            throw new IOException("metadata service unavailable");
        });
        ObsStorageService service = new ObsStorageService(properties, credentials,
                clientCredentials -> new FakeObsApi());

        // Startup must not fail: a CCE pod can be scheduled before the metadata path is usable.
        service.init();

        assertThatThrownBy(() -> service.exists("packages/a.zip"))
                .isInstanceOf(StorageAccessException.class)
                .extracting("operation").isEqualTo("exists");
    }

    @Test
    void closingReleasesTheCurrentClient() {
        FakeObsApi api = new FakeObsApi();
        ObsStorageService service = service(staticProperties(), api);
        service.exists("packages/a.zip");

        service.close();

        assertThat(api.closed).isTrue();
    }

    private ObsStorageService service(ObsStorageProperties properties, ObsApi api) {
        return new ObsStorageService(properties, new ObsCredentialProvider(properties, unreachableMetadata()),
                clientCredentials -> api);
    }

    private ObsCredentialProvider.MetadataClient unreachableMetadata() {
        return (url, timeout) -> {
            throw new AssertionError("the metadata service must not be used with static credentials");
        };
    }

    private ObsStorageProperties staticProperties() {
        ObsStorageProperties properties = new ObsStorageProperties();
        properties.setBucket("skillhub");
        properties.setEndpoint("https://obs.cn-north-4.myhuaweicloud.com");
        properties.setAccessKey("static-ak");
        properties.setSecretKey("static-sk");
        return properties;
    }

    private ObsStorageProperties metadataProperties() {
        ObsStorageProperties properties = new ObsStorageProperties();
        properties.setBucket("skillhub");
        properties.setEndpoint("https://obs.cn-north-4.myhuaweicloud.com");
        properties.setCredentialMode(ObsStorageProperties.CredentialMode.METADATA);
        properties.setCredentialTtl(Duration.ZERO);
        return properties;
    }

    private static String metadataDocument(String accessKey) {
        return "{\"credential\":{\"access\":\"" + accessKey + "\",\"secret\":\"sk\",\"securitytoken\":\"token\"}}";
    }

    /** In-memory {@link ObsApi}; nothing here talks to OBS. */
    private static final class FakeObsApi implements ObsApi {
        private final Map<String, byte[]> objects = new LinkedHashMap<>();
        private final Map<String, String> contentTypes = new LinkedHashMap<>();
        private final Map<String, byte[]> putBodies = new LinkedHashMap<>();
        private final List<String> putKeys = new ArrayList<>();
        private final Map<String, Long> putSizes = new LinkedHashMap<>();
        private final Map<String, String> putContentTypes = new LinkedHashMap<>();
        private final List<String> deletedKeys = new ArrayList<>();
        private final List<Integer> deleteBatchSizes = new ArrayList<>();
        private final Set<String> failedDeletes = new LinkedHashSet<>();
        private final List<String> listPrefixes = new ArrayList<>();
        private final List<String> listMarkers = new ArrayList<>();
        private final List<Integer> listMaxKeys = new ArrayList<>();
        private final List<ObsObjectPage> listPages = new ArrayList<>();
        private final List<String> presignedGetKeys = new ArrayList<>();
        private final List<String> presignedGetDispositions = new ArrayList<>();
        private final List<Long> presignedGetExpiries = new ArrayList<>();
        private final List<String> presignedPutKeys = new ArrayList<>();
        private final List<String> presignedPutContentTypes = new ArrayList<>();
        private final List<Long> presignedPutExpiries = new ArrayList<>();

        private RuntimeException failure;
        private Path lastStagedBody;
        private boolean closed;

        @Override
        public void putObject(String key, Path body, long size, String contentType) {
            // Record the staged body first so failure tests can assert it was cleaned up.
            lastStagedBody = body;
            failIfConfigured();
            putKeys.add(key);
            putSizes.put(key, size);
            putContentTypes.put(key, contentType);
            byte[] staged;
            try {
                staged = Files.readAllBytes(body);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            putBodies.put(key, staged);
            objects.put(key, staged);
            contentTypes.put(key, contentType);
        }

        @Override
        public InputStream getObject(String key) {
            failIfConfigured();
            byte[] data = objects.get(key);
            if (data == null) {
                throw new IllegalStateException("missing object " + key);
            }
            return new ByteArrayInputStream(data);
        }

        @Override
        public boolean exists(String key) {
            failIfConfigured();
            return objects.containsKey(key);
        }

        @Override
        public ObjectMetadata getMetadata(String key) {
            failIfConfigured();
            byte[] data = objects.get(key);
            if (data == null) {
                throw new IllegalStateException("missing object " + key);
            }
            return new ObjectMetadata(data.length, contentTypes.get(key), Instant.parse("2024-01-01T00:00:00Z"));
        }

        @Override
        public void deleteObject(String key) {
            failIfConfigured();
            deletedKeys.add(key);
            objects.remove(key);
        }

        @Override
        public List<String> deleteObjects(List<String> keys) {
            failIfConfigured();
            deleteBatchSizes.add(keys.size());
            deletedKeys.addAll(keys);
            keys.forEach(objects::remove);
            return keys.stream().filter(failedDeletes::contains).toList();
        }

        @Override
        public ObsObjectPage listObjects(String prefix, String marker, int maxKeys) {
            failIfConfigured();
            listPrefixes.add(prefix);
            listMarkers.add(marker);
            listMaxKeys.add(maxKeys);
            if (listPages.isEmpty()) {
                return new ObsObjectPage(List.of(), null, false);
            }
            return listPages.remove(0);
        }

        @Override
        public String presignGet(String key, long expiresSeconds, String contentDisposition) {
            failIfConfigured();
            presignedGetKeys.add(key);
            presignedGetDispositions.add(contentDisposition);
            presignedGetExpiries.add(expiresSeconds);
            return "https://obs.example.test/skillhub/" + key;
        }

        @Override
        public PresignedUpload presignPut(String key, long expiresSeconds, String contentType) {
            failIfConfigured();
            presignedPutKeys.add(key);
            presignedPutContentTypes.add(contentType);
            presignedPutExpiries.add(expiresSeconds);
            return new PresignedUpload("https://obs.example.test/skillhub/" + key,
                    Map.of("Content-Type", contentType));
        }

        @Override
        public void close() {
            closed = true;
        }

        private void failIfConfigured() {
            if (failure != null) {
                throw failure;
            }
        }
    }
}
