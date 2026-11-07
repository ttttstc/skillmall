package com.iflytek.skillhub.dto.cli;

public record CliPublishResponse(
        String namespace,
        String slug,
        String version,
        String visibility,
        /** Version status after the upload: PUBLISHED for a direct publish, UPLOADED for PRIVATE. */
        String status
) {}
