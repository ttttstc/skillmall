package com.iflytek.skillhub.gitcode;

/**
 * The error codes the GitCode import contract exposes to clients.
 *
 * <p>The list is closed on purpose: every failure the importer can attribute to the remote source
 * or its layout maps onto one of these seven codes, so the publish wizard can branch on them.
 * Failures the publish pipeline itself detects keep that pipeline's existing codes.</p>
 */
public final class GitcodeErrors {

    /** The pasted address is not a gitcode.com repository URL. */
    public static final String URL_INVALID = "error.gitcode.url.invalid";
    /** GitCode could not be reached, answered with a server error, or the budget ran out. */
    public static final String UNREACHABLE = "error.gitcode.unreachable";
    /** GitCode rate-limited the anonymous read (HTTP 429); the caller should retry later. */
    public static final String RATE_LIMITED = "error.gitcode.rateLimited";
    /** The repository does not exist, or is not publicly readable. */
    public static final String REPO_NOT_FOUND = "error.gitcode.repo.notFound";
    /** No {@code SKILL.md} was found in the scanned subtree (or the requested path is gone). */
    public static final String SKILL_MD_NOT_FOUND = "error.gitcode.skillMd.notFound";
    /** The subtree contains several skill directories and the request did not choose one. */
    public static final String SKILL_AMBIGUOUS = "error.gitcode.skillAmbiguous";
    /** A file or the whole package exceeds the import size limits. */
    public static final String TOO_LARGE = "error.gitcode.tooLarge";
    /** The version supplied by the user is not a plain version token. */
    public static final String VERSION_INVALID = "error.gitcode.version.invalid";
    /** The subtree has too many files or directories for an import. */
    public static final String TOO_MANY_FILES = "error.gitcode.tooManyFiles";

    private GitcodeErrors() {
    }
}
