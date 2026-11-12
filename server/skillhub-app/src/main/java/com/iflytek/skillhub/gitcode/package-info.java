/**
 * GitCode repository import: address parsing, anonymous API access, skill discovery, and the
 * import orchestration that reuses the existing publish pipeline.
 *
 * <p>This package is provider-specific and deliberately not generalized with
 * {@code githubsync}: phase one imports a snapshot of one repository directory, it does not keep a
 * source in sync. The binding entity therefore lives here rather than in a shared source model, and
 * the package owns exactly one persisted table ({@code gitcode_source_binding}).</p>
 */
package com.iflytek.skillhub.gitcode;
