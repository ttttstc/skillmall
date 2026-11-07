package com.iflytek.skillhub.domain.skill;

import java.util.Locale;

/**
 * Fixed first-level marketplace category of a Skill (GitHub issue #5).
 *
 * <p>This is a closed enum, not a taxonomy: the product ships exactly these six first-level
 * categories and deliberately does not model second- or third-level directories. Adding a
 * category is a schema, translation and UI change — never a runtime data-entry action — which is
 * what keeps every filter translatable, indexable and free of user-invented buckets.
 *
 * <p>{@link #OTHER} is also the documented default: skills created before this field existed, and
 * every write path that does not choose a category (GitHub sync, bundles, promotion), land here
 * rather than in a guessed category. See {@code docs/29-skill-domain-categories.md}.
 */
public enum SkillDomain {
    DESIGN,
    DEVELOPMENT,
    TESTING,
    ENGINEERING,
    PROJECT_MANAGEMENT,
    OTHER;

    /** Category used when a caller does not choose one. */
    public static final SkillDomain DEFAULT = OTHER;

    /**
     * Parses a wire value ({@code DEVELOPMENT}, {@code development}, {@code project-management}).
     *
     * @throws IllegalArgumentException when the value is not one of the six categories, so callers
     *         can turn it into a localized bad-request instead of silently widening the query
     */
    public static SkillDomain fromWire(String rawValue) {
        String normalized = rawValue == null ? "" : rawValue.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown skill domain: " + rawValue, ex);
        }
    }

    /** Null-tolerant variant used by optional query parameters: blank means "no filter". */
    public static SkillDomain parseFilter(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        return fromWire(rawValue);
    }
}
