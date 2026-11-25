package com.iflytek.skillhub.auth.gitcode;

/**
 * Normalized GitCode account resolved from a credential.
 *
 * @param id GitCode account id, a stable 24-character hexadecimal identifier
 * @param login GitCode login name, which a user may change
 * @param name display name reported by GitCode, possibly blank
 * @param avatarUrl avatar URL, possibly null
 * @param email email reported by GitCode, unverified and therefore never trusted for access
 */
public record GitCodeIdentity(String id, String login, String name, String avatarUrl, String email) {

    public boolean hasStableId() {
        return id != null && !id.isBlank();
    }

    public String displayName() {
        return name != null && !name.isBlank() ? name : login;
    }
}
