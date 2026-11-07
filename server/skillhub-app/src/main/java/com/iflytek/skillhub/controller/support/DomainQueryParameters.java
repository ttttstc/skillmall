package com.iflytek.skillhub.controller.support;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.SkillDomain;

/**
 * Shared parsing of the {@code domain} query parameter for the public discovery endpoints.
 *
 * <p>An unknown category is rejected instead of being ignored: silently dropping the filter would
 * answer a filtered request with the whole catalogue, which looks like a working category that
 * happens to contain everything.
 */
public final class DomainQueryParameters {

    private DomainQueryParameters() {
    }

    /** @return {@code null} when the caller passed nothing (no filter) */
    public static SkillDomain parse(String rawValue) {
        try {
            return SkillDomain.parseFilter(rawValue);
        } catch (IllegalArgumentException exception) {
            throw new DomainBadRequestException("error.skill.domain.invalid", rawValue);
        }
    }
}
