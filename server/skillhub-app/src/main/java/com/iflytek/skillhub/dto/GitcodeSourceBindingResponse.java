package com.iflytek.skillhub.dto;

import com.iflytek.skillhub.gitcode.GitcodeLinkType;
import com.iflytek.skillhub.gitcode.GitcodeRefType;

import java.time.Instant;

/**
 * One namespace-scoped GitCode import binding, as listed for the publisher who owns it.
 */
public record GitcodeSourceBindingResponse(
        String owner,
        String repository,
        String path,
        String ref,
        GitcodeRefType refType,
        String commitSha,
        GitcodeLinkType linkType,
        String visibility,
        Instant lastImportedAt,
        Long skillId,
        Long skillVersionId
) {
}
