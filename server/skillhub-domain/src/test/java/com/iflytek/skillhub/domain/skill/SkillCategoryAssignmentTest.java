package com.iflytek.skillhub.domain.skill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The parent/child invariant for a skill's two category columns.
 *
 * <p>The database enforces the same rule through a composite foreign key, so a skill is never
 * persisted with a second-level category (or a dangling one) while its first-level column is
 * empty. This test covers the in-memory object, which is what every caller actually touches first.
 */
class SkillCategoryAssignmentTest {

    @Test
    void assignCategories_writesBothLevels() {
        Skill skill = skill();

        skill.assignCategories(10L, 20L);

        assertThat(skill.getCategoryId()).isEqualTo(10L);
        assertThat(skill.getSubCategoryId()).isEqualTo(20L);
    }

    @Test
    void assignCategories_allowsPrimaryWithoutSecondary() {
        Skill skill = skill();

        skill.assignCategories(10L, null);

        assertThat(skill.getCategoryId()).isEqualTo(10L);
        assertThat(skill.getSubCategoryId()).isNull();
    }

    @Test
    void assignCategories_clearsSecondaryWhenPrimaryIsCleared() {
        Skill skill = skill();
        skill.assignCategories(10L, 20L);

        // "Unbind" a skill whose category was removed: the child must not survive on its own,
        // otherwise the row violates the composite key on the next flush.
        skill.assignCategories(null, 20L);

        assertThat(skill.getCategoryId()).isNull();
        assertThat(skill.getSubCategoryId()).isNull();
    }

    @Test
    void assignCategories_canMoveSkillToAnotherPrimaryWithItsOwnChild() {
        Skill skill = skill();
        skill.assignCategories(10L, 20L);

        skill.assignCategories(11L, 21L);

        assertThat(skill.getCategoryId()).isEqualTo(11L);
        assertThat(skill.getSubCategoryId()).isEqualTo(21L);
    }

    @Test
    void setSubCategoryId_onlyTouchesTheSecondLevel() {
        Skill skill = skill();
        skill.assignCategories(10L, null);

        skill.setSubCategoryId(20L);

        assertThat(skill.getCategoryId()).isEqualTo(10L);
        assertThat(skill.getSubCategoryId()).isEqualTo(20L);
    }

    private static Skill skill() {
        return new Skill(1L, "demo", "owner-1", SkillVisibility.PRIVATE);
    }
}
