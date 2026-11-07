package com.iflytek.skillhub.dto;

/** Resolve/preview request for importing a skill from a pinned GitCode snapshot. */
public record GitCodeImportResolveRequest(String repoUrl, String commit, String path) {
}
