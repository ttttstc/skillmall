package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Required administrator reason for taking a Skill down.
 *
 * <p>A takedown must be explainable after the fact, so the reason is mandatory rather than
 * best-effort: a request without it is rejected before any state changes. The same value is stored
 * on the Skill and copied into the audit entry.
 */
public record AdminSkillTakedownRequest(
        @NotBlank @Size(max = 500) String reason
) {
}
