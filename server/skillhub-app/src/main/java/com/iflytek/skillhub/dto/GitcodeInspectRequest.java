package com.iflytek.skillhub.dto;

/**
 * Preview request for a GitCode repository address.
 *
 * <p>{@code ref} and {@code path} are optional: they default to whatever the address itself
 * carries, and then to the repository default branch and the repository root.</p>
 */
public record GitcodeInspectRequest(
        String repoUrl,
        String ref,
        String path
) {
}
