package com.iflytek.skillhub.domain.skill;

import java.util.Optional;

/** Persistence port for {@link SkillImportSource} provenance rows. */
public interface SkillImportSourceRepository {

    SkillImportSource save(SkillImportSource source);

    Optional<SkillImportSource> findBySkillVersionId(Long skillVersionId);
}
