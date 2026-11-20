package com.iflytek.skillhub.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in real OBS round trip. Disabled unless {@code -Dskillhub.storage.obs.contract=true} is
 * passed; an enabled run with missing configuration fails rather than skips.
 *
 * <p>Configuration is read from the environment, for example:
 * {@code SKILLHUB_OBS_TEST_BUCKET}, {@code SKILLHUB_OBS_TEST_ENDPOINT},
 * {@code SKILLHUB_STORAGE_OBS_ACCESS_KEY} and {@code SKILLHUB_STORAGE_OBS_SECRET_KEY}. Inside CCE
 * the credentials may instead come from the metadata service. The bucket must already exist: OBS
 * bucket creation is not part of this adapter.
 */
@EnabledIfSystemProperty(named = "skillhub.storage.obs.contract", matches = "true")
class ObsStorageContractTest {
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void configuredBucketMustSatisfyTheObsContract() throws Exception {
        ObsStorageService service = configuredService();
        try {
            service.init();
            new ObsStorageLiveProbe(service).verify();
        } finally {
            service.close();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "skillhub.storage.obs.contract.full", matches = "true")
    @Timeout(value = 300, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void realBucketMustTraverseMultiplePagesAndDeleteMoreThanOneBatch() {
        ObsStorageService service = configuredService();
        String prefix = "skillhub-obs-diagnostics/" + UUID.randomUUID() + "/pagination/";
        var keys = new ArrayList<String>();
        System.out.println("Pagination diagnostic object prefix: " + prefix);
        Throwable failure = null;
        try {
            service.init();
            for (int index = 0; index < 1001; index++) {
                String key = prefix + index;
                keys.add(key);
                service.putObject(key, new ByteArrayInputStream(new byte[]{42}), 1, "application/octet-stream");
            }
            assertThat(service.listPrefixUsage(prefix, 2000))
                    .contains(new StoragePrefixUsage(1001, 1001, true));
            assertThat(service.listPrefixUsage(prefix, 1000))
                    .contains(new StoragePrefixUsage(1000, 1000, false));
            // A full batch may report transient per-object failures; keep them visible and let the
            // caller recover idempotently with a bound instead of trusting the HTTP status.
            for (int attempt = 1; ; attempt++) {
                try {
                    service.deleteObjects(keys);
                    break;
                } catch (StorageAccessException exception) {
                    if (attempt == 3) {
                        throw exception;
                    }
                    System.out.println("Batch deletion reported a failure; caller recovery attempt "
                            + attempt + "; " + (exception.getCause() == null
                            ? "unknown" : exception.getCause().getClass().getSimpleName()));
                }
            }
            assertThat(service.listPrefixUsage(prefix, 2000)).contains(new StoragePrefixUsage(0, 0, true));
            keys.clear(); // Absence was verified; do not issue a redundant cleanup load burst.
        } catch (RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                service.deleteObjects(keys);
            } catch (RuntimeException exception) {
                if (failure != null) {
                    failure.addSuppressed(exception);
                } else {
                    throw exception;
                }
            } finally {
                service.close();
            }
        }
    }

    private ObsStorageService configuredService() {
        ObsStorageProperties properties = Binder.get(new StandardEnvironment())
                .bind("skillhub.storage.obs", ObsStorageProperties.class)
                .orElseThrow(() -> new IllegalStateException("OBS diagnostic configuration is missing"));
        if (isBlank(properties.getBucket()) || isBlank(properties.getEndpoint())) {
            throw new IllegalStateException(
                    "OBS diagnostic requires SKILLHUB_STORAGE_OBS_BUCKET and SKILLHUB_STORAGE_OBS_ENDPOINT");
        }
        if (properties.resolveCredentialMode() == ObsStorageProperties.CredentialMode.STATIC
                && !properties.hasStaticCredentials()) {
            throw new IllegalStateException("OBS diagnostic static credentials are incomplete");
        }
        return new ObsStorageService(properties);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
