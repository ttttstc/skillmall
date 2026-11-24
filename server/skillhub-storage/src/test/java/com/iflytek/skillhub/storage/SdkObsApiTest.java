package com.iflytek.skillhub.storage;

import com.obs.services.ObsClient;
import com.obs.services.exception.ObsException;
import com.obs.services.model.DeleteObjectsResult;
import com.obs.services.model.HttpMethodEnum;
import com.obs.services.model.ListObjectsRequest;
import com.obs.services.model.ObjectListing;
import com.obs.services.model.ObsObject;
import com.obs.services.model.TemporarySignatureRequest;
import com.obs.services.model.TemporarySignatureResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SdkObsApiTest {

    @Test
    void missingObjectIsReportedAsAbsentAndAuthorizationFailureIsNot() {
        ObsClient client = mock(ObsClient.class);
        SdkObsApi api = new SdkObsApi(client, client, "skillhub");

        ObsException notFound = new ObsException("missing");
        notFound.setResponseCode(404);
        when(client.getObjectMetadata("skillhub", "private/missing.zip")).thenThrow(notFound);
        assertThat(api.exists("private/missing.zip")).isFalse();

        ObsException forbidden = new ObsException("denied");
        forbidden.setResponseCode(403);
        when(client.getObjectMetadata("skillhub", "private/denied.zip")).thenThrow(forbidden);
        assertThatThrownBy(() -> api.exists("private/denied.zip")).isSameAs(forbidden);
    }

    @Test
    void metadataMapsSizeContentTypeAndLastModified() {
        ObsClient client = mock(ObsClient.class);
        SdkObsApi api = new SdkObsApi(client, client, "skillhub");
        var metadata = new com.obs.services.model.ObjectMetadata();
        metadata.setContentLength(5L);
        metadata.setContentType("application/zip");
        metadata.setLastModified(new Date(1_700_000_000_000L));
        when(client.getObjectMetadata("skillhub", "packages/demo.zip")).thenReturn(metadata);

        ObjectMetadata result = api.getMetadata("packages/demo.zip");

        assertThat(result.size()).isEqualTo(5);
        assertThat(result.contentType()).isEqualTo("application/zip");
        assertThat(result.lastModified()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
    }

    @Test
    void metadataWithoutAContentLengthFailsLoudly() {
        ObsClient client = mock(ObsClient.class);
        SdkObsApi api = new SdkObsApi(client, client, "skillhub");
        when(client.getObjectMetadata("skillhub", "packages/demo.zip"))
                .thenReturn(new com.obs.services.model.ObjectMetadata());

        assertThatThrownBy(() -> api.getMetadata("packages/demo.zip"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("content length");
    }

    @Test
    void putObjectUploadsTheStagedFileWithTheGivenContentType(@org.junit.jupiter.api.io.TempDir Path directory)
            throws IOException {
        ObsClient client = mock(ObsClient.class);
        SdkObsApi api = new SdkObsApi(client, client, "skillhub");
        Path staged = Files.write(directory.resolve("package.zip"), "hello".getBytes(StandardCharsets.UTF_8));

        api.putObject("packages/demo.zip", staged, 5, "application/zip");

        var metadata = ArgumentCaptor.forClass(com.obs.services.model.ObjectMetadata.class);
        var file = ArgumentCaptor.forClass(File.class);
        verify(client).putObject(eq("skillhub"), eq("packages/demo.zip"), file.capture(), metadata.capture());
        assertThat(metadata.getValue().getContentType()).isEqualTo("application/zip");
        assertThat(metadata.getValue().getContentLength()).isEqualTo(5L);
        assertThat(file.getValue()).isEqualTo(staged.toFile());
    }

    @Test
    void batchDeletionReportsPerObjectErrors() {
        ObsClient client = mock(ObsClient.class);
        SdkObsApi api = new SdkObsApi(client, client, "skillhub");
        when(client.deleteObjects(any())).thenReturn(new DeleteObjectsResult(
                List.of(new DeleteObjectsResult.DeleteObjectResult("packages/a.zip", null, false, null)),
                List.of(new DeleteObjectsResult.ErrorResult("packages/b.zip", null, "AccessDenied", "denied"))));

        List<String> failed = api.deleteObjects(List.of("packages/a.zip", "packages/b.zip"));

        assertThat(failed).containsExactly("packages/b.zip");
    }

    @Test
    void batchDeletionDetectsSilentlyOmittedKeys() {
        ObsClient client = mock(ObsClient.class);
        SdkObsApi api = new SdkObsApi(client, client, "skillhub");
        when(client.deleteObjects(any())).thenReturn(new DeleteObjectsResult(
                List.of(new DeleteObjectsResult.DeleteObjectResult("packages/a.zip", null, false, null)),
                List.of()));

        List<String> failed = api.deleteObjects(List.of("packages/a.zip", "packages/b.zip"));

        assertThat(failed).containsExactly("packages/b.zip");
    }

    @Test
    void batchDeletionAcceptsAFullyReportedSuccess() {
        ObsClient client = mock(ObsClient.class);
        SdkObsApi api = new SdkObsApi(client, client, "skillhub");
        when(client.deleteObjects(any())).thenReturn(new DeleteObjectsResult(
                List.of(new DeleteObjectsResult.DeleteObjectResult("packages/a.zip", null, false, null),
                        new DeleteObjectsResult.DeleteObjectResult("packages/b.zip", null, false, null)),
                List.of()));

        assertThat(api.deleteObjects(List.of("packages/a.zip", "packages/b.zip"))).isEmpty();
    }

    @Test
    void listingMapsObjectsAndKeepsTheMarker() {
        ObsClient client = mock(ObsClient.class);
        SdkObsApi api = new SdkObsApi(client, client, "skillhub");
        ObjectListing listing = mock(ObjectListing.class);
        ObsObject first = new ObsObject();
        first.setObjectKey("staging/a.zip");
        var metadata = new com.obs.services.model.ObjectMetadata();
        metadata.setContentLength(30L);
        metadata.setLastModified(new Date(0L));
        first.setMetadata(metadata);
        when(listing.getObjects()).thenReturn(List.of(first));
        when(listing.isTruncated()).thenReturn(true);
        when(listing.getNextMarker()).thenReturn("staging/a.zip");
        when(client.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);

        ObsApi.ObsObjectPage page = api.listObjects("staging/", "marker-1", 10);

        assertThat(page.truncated()).isTrue();
        assertThat(page.nextMarker()).isEqualTo("staging/a.zip");
        assertThat(page.objects()).containsExactly(
                new ObsApi.ObsObjectSummary("staging/a.zip", 30L, Instant.EPOCH));
        var request = ArgumentCaptor.forClass(ListObjectsRequest.class);
        verify(client).listObjects(request.capture());
        assertThat(request.getValue().getPrefix()).isEqualTo("staging/");
        assertThat(request.getValue().getMarker()).isEqualTo("marker-1");
        assertThat(request.getValue().getMaxKeys()).isEqualTo(10);
    }

    @Test
    void presignedGetSignsTheDownloadFilenameIntoTheQuery() {
        ObsClient client = mock(ObsClient.class);
        SdkObsApi api = new SdkObsApi(client, client, "skillhub");
        when(client.createTemporarySignature(any(TemporarySignatureRequest.class)))
                .thenReturn(new TemporarySignatureResponse("https://example.test/signed"));

        String url = api.presignGet("packages/demo.zip", 600, "attachment; filename*=UTF-8''demo.zip");

        assertThat(url).isEqualTo("https://example.test/signed");
        var request = ArgumentCaptor.forClass(TemporarySignatureRequest.class);
        verify(client).createTemporarySignature(request.capture());
        assertThat(request.getValue().getMethod()).isEqualTo(HttpMethodEnum.GET);
        assertThat(request.getValue().getExpires()).isEqualTo(600);
        assertThat(request.getValue().getBucketName()).isEqualTo("skillhub");
        assertThat(request.getValue().getObjectKey()).isEqualTo("packages/demo.zip");
        assertThat(request.getValue().getQueryParams())
                .containsEntry("response-content-disposition", "attachment; filename*=UTF-8''demo.zip");
    }

    @Test
    void presignedGetFailureIsDetectedFromTheAbsentUrlOnly() {
        // createTemporarySignature signs locally and reports no HTTP status, so an empty URL is the
        // only failure signal. The reference Python implementation crashed by comparing the status.
        ObsClient client = mock(ObsClient.class);
        SdkObsApi api = new SdkObsApi(client, client, "skillhub");
        when(client.createTemporarySignature(any(TemporarySignatureRequest.class)))
                .thenReturn(new TemporarySignatureResponse(null));

        assertThatThrownBy(() -> api.presignGet("packages/demo.zip", 600, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no presigned GET URL");
    }

    @Test
    void presignedPutBindsTheContentTypeAndReturnsOnlySenderHeaders() {
        ObsClient client = mock(ObsClient.class);
        SdkObsApi api = new SdkObsApi(client, client, "skillhub");
        TemporarySignatureResponse response = mock(TemporarySignatureResponse.class);
        when(response.getSignedUrl()).thenReturn("https://example.test/put");
        when(response.getActualSignedRequestHeaders()).thenReturn(Map.of(
                "Host", "skillhub.obs.cn-north-4.myhuaweicloud.com",
                "Content-Type", "application/zip"));
        when(client.createTemporarySignature(any(TemporarySignatureRequest.class))).thenReturn(response);

        PresignedUpload upload = api.presignPut("staging/demo.zip", 600, "application/zip");

        assertThat(upload.url()).isEqualTo("https://example.test/put");
        assertThat(upload.headers())
                .containsEntry("Content-Type", "application/zip")
                .doesNotContainKey("Host");
        var request = ArgumentCaptor.forClass(TemporarySignatureRequest.class);
        verify(client).createTemporarySignature(request.capture());
        assertThat(request.getValue().getMethod()).isEqualTo(HttpMethodEnum.PUT);
        assertThat(request.getValue().getHeaders()).containsEntry("Content-Type", "application/zip");
    }

    @Test
    void presignedPutReportsFailureWhenNoUrlComesBack() {
        ObsClient client = mock(ObsClient.class);
        SdkObsApi api = new SdkObsApi(client, client, "skillhub");
        TemporarySignatureResponse response = mock(TemporarySignatureResponse.class);
        when(response.getSignedUrl()).thenReturn("  ");
        when(client.createTemporarySignature(any(TemporarySignatureRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> api.presignPut("staging/demo.zip", 600, "application/zip"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no presigned PUT URL");
    }

    @Test
    void presignedUrlWithoutTheSecurityTokenIsRejectedForTemporaryCredentials() {
        ObsClient client = mock(ObsClient.class);
        SdkObsApi api = new SdkObsApi(client, client, "skillhub", "temporary-token");
        when(client.createTemporarySignature(any(TemporarySignatureRequest.class)))
                .thenReturn(new TemporarySignatureResponse(
                        "https://example.test/signed?AccessKeyId=AK&Expires=1&Signature=abc%3D"));

        assertThatThrownBy(() -> api.presignGet("packages/demo.zip", 600, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("x-obs-security-token");
    }

    @Test
    void presignedUrlWithTheSecurityTokenIsAccepted() {
        ObsClient client = mock(ObsClient.class);
        SdkObsApi api = new SdkObsApi(client, client, "skillhub", "temporary-token");
        String signed = "https://example.test/signed?AccessKeyId=AK&Expires=1"
                + "&x-obs-security-token=temporary-token&Signature=abc%3D";
        when(client.createTemporarySignature(any(TemporarySignatureRequest.class)))
                .thenReturn(new TemporarySignatureResponse(signed));

        assertThat(api.presignGet("packages/demo.zip", 600, null)).isEqualTo(signed);
    }

    @Test
    void closingBothClientsIsSafeAndClosesTheSharedOneOnce() throws Exception {
        ObsClient shared = mock(ObsClient.class);
        new SdkObsApi(shared, shared, "skillhub").close();
        verify(shared, times(1)).close();

        ObsClient operationClient = mock(ObsClient.class);
        ObsClient signingClient = mock(ObsClient.class);
        new SdkObsApi(operationClient, signingClient, "skillhub").close();
        verify(operationClient).close();
        verify(signingClient).close();
    }
}
