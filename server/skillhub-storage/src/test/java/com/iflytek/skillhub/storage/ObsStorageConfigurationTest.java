package com.iflytek.skillhub.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deployment-facing contract: the environment variable names the Helm chart sets must bind to
 * {@link ObsStorageProperties}, and {@code skillhub.storage.provider=obs} must select exactly one
 * storage adapter.
 */
class ObsStorageConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(StorageProperties.class, ObsStorageProperties.class,
                    LocalFileStorageService.class, ObsStorageService.class);

    @Test
    void environmentVariableNamesBindToTheObsProperties() {
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("SKILLHUB_STORAGE_OBS_BUCKET", "skillhub-cce");
        environment.put("SKILLHUB_STORAGE_OBS_ENDPOINT", "http://obs.cn-north-4.myhuaweicloud.com");
        environment.put("SKILLHUB_STORAGE_OBS_PUBLIC_ENDPOINT", "https://obs.cn-north-4.myhuaweicloud.com");
        environment.put("SKILLHUB_STORAGE_OBS_PRESIGN_EXPIRY", "PT15M");
        environment.put("SKILLHUB_STORAGE_OBS_CREDENTIAL_MODE", "metadata");
        environment.put("SKILLHUB_STORAGE_OBS_ACCESS_KEY", "ak");
        environment.put("SKILLHUB_STORAGE_OBS_SECRET_KEY", "sk");
        environment.put("SKILLHUB_STORAGE_OBS_METADATA_URL", "http://169.254.169.254/openstack/latest/securitykey");
        environment.put("SKILLHUB_STORAGE_OBS_METADATA_TIMEOUT", "PT3S");
        environment.put("SKILLHUB_STORAGE_OBS_CREDENTIAL_TTL", "PT45M");
        StandardEnvironment source = new StandardEnvironment();
        source.getPropertySources().addFirst(new SystemEnvironmentPropertySource("diagnostics", environment));

        ObsStorageProperties properties = Binder.get(source)
                .bind("skillhub.storage.obs", ObsStorageProperties.class).get();

        assertThat(properties.getBucket()).isEqualTo("skillhub-cce");
        assertThat(properties.getEndpoint()).isEqualTo("http://obs.cn-north-4.myhuaweicloud.com");
        assertThat(properties.getPublicEndpoint()).isEqualTo("https://obs.cn-north-4.myhuaweicloud.com");
        assertThat(properties.getPresignExpiry()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.getCredentialMode()).isEqualTo(ObsStorageProperties.CredentialMode.METADATA);
        assertThat(properties.getAccessKey()).isEqualTo("ak");
        assertThat(properties.getSecretKey()).isEqualTo("sk");
        assertThat(properties.getMetadataTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.getCredentialTtl()).isEqualTo(Duration.ofMinutes(45));
    }

    @Test
    void defaultsMatchTheAgreedConfigurationContract() {
        ObsStorageProperties properties = new ObsStorageProperties();

        assertThat(properties.getPresignExpiry()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.getCredentialMode()).isEqualTo(ObsStorageProperties.CredentialMode.AUTO);
        assertThat(properties.getMetadataUrl()).isEqualTo(ObsStorageProperties.DEFAULT_METADATA_URL);
        assertThat(properties.getMetadataTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getCredentialTtl()).isEqualTo(Duration.ofMinutes(50));
    }

    @Test
    void autoModePrefersStaticKeysAndFallsBackToMetadata() {
        ObsStorageProperties properties = new ObsStorageProperties();
        assertThat(properties.resolveCredentialMode()).isEqualTo(ObsStorageProperties.CredentialMode.METADATA);

        properties.setAccessKey("ak");
        properties.setSecretKey("sk");
        assertThat(properties.resolveCredentialMode()).isEqualTo(ObsStorageProperties.CredentialMode.STATIC);
    }

    @Test
    void presignEndpointFallsBackToTheOperationEndpoint() {
        ObsStorageProperties properties = new ObsStorageProperties();
        properties.setEndpoint("http://obs.cn-north-4.myhuaweicloud.com");
        assertThat(properties.resolvePresignEndpoint()).isEqualTo("http://obs.cn-north-4.myhuaweicloud.com");

        properties.setPublicEndpoint("https://cdn.example.test");
        assertThat(properties.resolvePresignEndpoint()).isEqualTo("https://cdn.example.test");
    }

    @Test
    void providerObsSelectsOnlyTheObsAdapter() {
        contextRunner
                .withPropertyValues(
                        "skillhub.storage.provider=obs",
                        "skillhub.storage.obs.bucket=skillhub",
                        "skillhub.storage.obs.endpoint=https://obs.cn-north-4.myhuaweicloud.com",
                        "skillhub.storage.obs.access-key=static-ak",
                        "skillhub.storage.obs.secret-key=static-sk")
                .run(context -> {
                    assertThat(context).hasSingleBean(ObsStorageService.class);
                    assertThat(context).doesNotHaveBean(LocalFileStorageService.class);
                });
    }

    @Test
    void providerOtherThanObsDoesNotRegisterTheObsAdapter() {
        contextRunner
                .withPropertyValues("skillhub.storage.provider=s3")
                .run(context -> assertThat(context).doesNotHaveBean(ObsStorageService.class));

        contextRunner
                .withPropertyValues("skillhub.storage.provider=local")
                .run(context -> {
                    assertThat(context).hasSingleBean(LocalFileStorageService.class);
                    assertThat(context).doesNotHaveBean(ObsStorageService.class);
                });
    }

    @Test
    void obsModeWithoutABucketFailsAtStartup() {
        contextRunner
                .withPropertyValues(
                        "skillhub.storage.provider=obs",
                        "skillhub.storage.obs.endpoint=https://obs.cn-north-4.myhuaweicloud.com",
                        "skillhub.storage.obs.access-key=static-ak",
                        "skillhub.storage.obs.secret-key=static-sk")
                .run(context -> assertThat(context).hasFailed());
    }
}
