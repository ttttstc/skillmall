package com.iflytek.skillhub.storage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit coverage for the OBS probe helpers that would otherwise only run with real credentials. */
class ObsStorageLiveProbeTest {

    @Test
    void obsPresignedUrlsAreRecognisedAndSigV4UrlsAreNot() {
        assertThat(ObsStorageLiveProbe.isObsPresignedUrl(
                "https://skillhub.obs.cn-north-4.myhuaweicloud.com/a.zip"
                        + "?AccessKeyId=AK&Expires=1795157294&Signature=abc%3D")).isTrue();
        assertThat(ObsStorageLiveProbe.isObsPresignedUrl(
                "https://bucket.s3.amazonaws.com/a.zip?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                        + "&X-Amz-Signature=abc")).isFalse();
        assertThat(ObsStorageLiveProbe.isObsPresignedUrl(null)).isFalse();
    }

    @Test
    void signatureTamperingChangesOneCharacterAndKeepsTheUrlShape() {
        String url = "https://skillhub.obs.cn-north-4.myhuaweicloud.com/a.zip"
                + "?AccessKeyId=AK&Expires=1795157294&Signature=AbC%2BdE%3D";
        String tampered = ObsStorageLiveProbe.tamperSignature(url).toString();

        assertThat(tampered).hasSameSizeAs(url);
        assertThat(tampered).startsWith("https://skillhub.obs.cn-north-4.myhuaweicloud.com/a.zip?AccessKeyId=AK"
                + "&Expires=1795157294&Signature=");
        assertThat(IntStream.range(0, url.length()).filter(index -> url.charAt(index) != tampered.charAt(index)).count())
                .isEqualTo(1);
    }

    @Test
    void tamperingRejectsUrlsWithoutAnObsSignature() {
        assertThatThrownBy(() -> ObsStorageLiveProbe.tamperSignature("https://example.test/a.zip?Expires=1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OBS query signature");
    }

    @Test
    void writeFailureStillCleansOnlyTheAttemptedObject() {
        ObjectStorageService storage = mock(ObjectStorageService.class);
        StorageAccessException writeFailure =
                new StorageAccessException("putObject", "object", new IllegalStateException());
        doThrow(writeFailure).when(storage).putObject(anyString(), any(InputStream.class), anyLong(), anyString());

        assertThatThrownBy(() -> new ObsStorageLiveProbe(storage).verify()).isSameAs(writeFailure);

        var key = ArgumentCaptor.forClass(String.class);
        verify(storage).deleteObject(key.capture());
        assertThat(key.getValue()).startsWith("skillhub-obs-diagnostics/").endsWith("package.zip");
    }

    @Test
    void probeDetectsFalseWriteSuccess() {
        ObjectStorageService storage = mock(ObjectStorageService.class);
        when(storage.exists(anyString())).thenReturn(false);

        assertThatThrownBy(() -> new ObsStorageLiveProbe(storage).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("uploaded object missing");
        verify(storage).deleteObject(anyString());
    }
}
