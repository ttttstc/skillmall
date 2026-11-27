-- Two-level skill taxonomy + scoped category administrators.
--
-- Why a reference table instead of extending the SkillDomain enum: docs/29 used to declare the
-- six first-level categories a closed set with no second level. The product now needs a second
-- level that category administrators maintain at runtime, so the catalogue has to live in data
-- rather than in code. The level-1 rows are seeded with the six existing wire values and keep a
-- one-to-one `legacy_domain` mapping, so no historical skill loses information.
--
-- Why the shape is enforced in SQL rather than in comments: "only two levels" is a hard product
-- constraint. A self-referencing table plus `level` alone would let a level-3 row in. The
-- generated `parent_level` column plus the composite foreign key makes "your parent must be a
-- level-1 row" a database fact, not a convention the next contributor has to remember.
--
-- Why category administrators get their own table instead of a platform role: platform roles are
-- global, carry no revocation history, and the only assignment path replaces a user's whole role
-- set. Granting a category administrator through it would silently strip an existing SUPER_ADMIN.
-- This mirrors organization_role_binding (V60): scoped subject, ACTIVE/REVOKED lifecycle,
-- granted/revoked actor and timestamp, and a partial unique index for the active grant.
--
-- Deliberately absent from this migration: any statement touching `skill.visibility`. The new
-- publishing policy makes *new* uploads private, but existing public skills must stay public.

-- ---------------------------------------------------------------------------
-- 1. Categories
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS skill_category (
    id            BIGSERIAL PRIMARY KEY,
    parent_id     BIGINT       REFERENCES skill_category (id) ON DELETE RESTRICT,
    level         SMALLINT     NOT NULL,
    slug          VARCHAR(64)  NOT NULL,
    -- Only meaningful on level-1 rows: maps the retired SkillDomain wire value onto a category.
    legacy_domain VARCHAR(32),
    sort_order    INTEGER      NOT NULL DEFAULT 0,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by    VARCHAR(128) REFERENCES user_account (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_category_level CHECK (level IN (1, 2)),
    CONSTRAINT ck_category_shape CHECK (
        (level = 1 AND parent_id IS NULL) OR
        (level = 2 AND parent_id IS NOT NULL)
    ),
    -- Constant-when-child marker so the composite FK below can demand a level-1 parent.
    parent_level  SMALLINT GENERATED ALWAYS AS (
        CASE WHEN parent_id IS NULL THEN NULL::SMALLINT ELSE 1::SMALLINT END
    ) STORED
);

-- FK targets. Both are supersets of the primary key, but a composite foreign key can only point
-- at a unique constraint, and these two are the ones the two composite FKs need.
ALTER TABLE skill_category
    DROP CONSTRAINT IF EXISTS uk_category_id_level;
ALTER TABLE skill_category
    ADD CONSTRAINT uk_category_id_level UNIQUE (id, level);

-- NULLS NOT DISTINCT (PostgreSQL 15+; this deployment targets 16) so the NULL parent_id of a
-- level-1 row still participates in uniqueness, which plain UNIQUE would not do.
ALTER TABLE skill_category
    DROP CONSTRAINT IF EXISTS uk_category_id_parent;
ALTER TABLE skill_category
    ADD CONSTRAINT uk_category_id_parent UNIQUE NULLS NOT DISTINCT (id, parent_id);

ALTER TABLE skill_category
    DROP CONSTRAINT IF EXISTS fk_category_parent_is_root;
ALTER TABLE skill_category
    ADD CONSTRAINT fk_category_parent_is_root
    FOREIGN KEY (parent_id, parent_level) REFERENCES skill_category (id, level);

-- slug is unique among level-1 rows, and unique among the children of one parent. A plain
-- UNIQUE(parent_id, slug) cannot express the root case because NULL parents never collide.
CREATE UNIQUE INDEX IF NOT EXISTS uk_category_root_slug
    ON skill_category (slug) WHERE parent_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_category_child_slug
    ON skill_category (parent_id, slug) WHERE parent_id IS NOT NULL;

-- Exactly one category may claim a given legacy wire value.
CREATE UNIQUE INDEX IF NOT EXISTS uk_category_legacy_domain
    ON skill_category (legacy_domain) WHERE legacy_domain IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_category_parent_sort
    ON skill_category (parent_id, sort_order, id);

-- ---------------------------------------------------------------------------
-- 2. Category translations
-- ---------------------------------------------------------------------------

-- Same shape as label_translation: names are data because administrators create categories at
-- runtime, so they cannot live in the frontend i18n bundles (which would demand four edits per
-- new category and still render a raw key for anything missed).
CREATE TABLE IF NOT EXISTS skill_category_translation (
    id           BIGSERIAL PRIMARY KEY,
    category_id  BIGINT       NOT NULL REFERENCES skill_category (id) ON DELETE CASCADE,
    locale       VARCHAR(16)  NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_category_translation_locale UNIQUE (category_id, locale)
);

CREATE INDEX IF NOT EXISTS idx_category_translation_locale
    ON skill_category_translation (locale, category_id);

-- ---------------------------------------------------------------------------
-- 3. Scoped category administrators
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS skill_category_admin (
    id          VARCHAR(64)  PRIMARY KEY,
    category_id BIGINT       NOT NULL,
    user_id     VARCHAR(128) NOT NULL REFERENCES user_account (id),
    status      VARCHAR(16)  NOT NULL,
    granted_by  VARCHAR(128) NOT NULL,
    granted_at  TIMESTAMPTZ  NOT NULL,
    revoked_by  VARCHAR(128),
    revoked_at  TIMESTAMPTZ,
    version     BIGINT       NOT NULL DEFAULT 0,
    -- Constant marker: administrators only ever attach to a level-1 category.
    root_level  SMALLINT GENERATED ALWAYS AS (1::SMALLINT) STORED,
    CONSTRAINT ck_category_admin_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_category_admin_revocation CHECK (
        (status = 'ACTIVE'  AND revoked_by IS NULL     AND revoked_at IS NULL) OR
        (status = 'REVOKED' AND revoked_by IS NOT NULL AND revoked_at IS NOT NULL)
    )
);

ALTER TABLE skill_category_admin
    DROP CONSTRAINT IF EXISTS fk_category_admin_is_root;
ALTER TABLE skill_category_admin
    ADD CONSTRAINT fk_category_admin_is_root
    FOREIGN KEY (category_id, root_level) REFERENCES skill_category (id, level);

-- One active grant per (category, user); revoked rows stay for the audit trail.
CREATE UNIQUE INDEX IF NOT EXISTS uk_category_admin_active
    ON skill_category_admin (category_id, user_id) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_category_admin_user_active
    ON skill_category_admin (user_id) WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- 4. Skill columns
-- ---------------------------------------------------------------------------

ALTER TABLE skill ADD COLUMN IF NOT EXISTS category_id     BIGINT;
ALTER TABLE skill ADD COLUMN IF NOT EXISTS sub_category_id BIGINT;

ALTER TABLE skill
    DROP CONSTRAINT IF EXISTS fk_skill_category;
ALTER TABLE skill
    ADD CONSTRAINT fk_skill_category
    FOREIGN KEY (category_id) REFERENCES skill_category (id) ON DELETE RESTRICT;

ALTER TABLE skill
    DROP CONSTRAINT IF EXISTS fk_skill_sub_category;
ALTER TABLE skill
    ADD CONSTRAINT fk_skill_sub_category
    FOREIGN KEY (sub_category_id) REFERENCES skill_category (id) ON DELETE RESTRICT;

-- Composite FK: a skill's sub-category must be a child of its own category. Plain two-column
-- FKs cannot express that, and it is the invariant the whole two-level UI depends on.
ALTER TABLE skill
    DROP CONSTRAINT IF EXISTS fk_skill_sub_under_category;
ALTER TABLE skill
    ADD CONSTRAINT fk_skill_sub_under_category
    FOREIGN KEY (sub_category_id, category_id)
    REFERENCES skill_category (id, parent_id);

-- MATCH SIMPLE skips the composite FK entirely when either column is NULL, so "has a sub-category
-- but no category" would slip through. Close that explicitly.
ALTER TABLE skill
    DROP CONSTRAINT IF EXISTS ck_skill_sub_requires_category;
ALTER TABLE skill
    ADD CONSTRAINT ck_skill_sub_requires_category CHECK (
        sub_category_id IS NULL OR category_id IS NOT NULL
    );

-- Filtering always rides along with the public-distribution predicate, so these are partial
-- indexes shaped like idx_skill_domain_public rather than full-table indexes.
CREATE INDEX IF NOT EXISTS idx_skill_category_public
    ON skill (category_id) WHERE status = 'ACTIVE' AND hidden = FALSE;
CREATE INDEX IF NOT EXISTS idx_skill_sub_category_public
    ON skill (sub_category_id) WHERE status = 'ACTIVE' AND hidden = FALSE;

-- Creator (not owner) is its own filter dimension. Ownership transfers must not rewrite history.
CREATE INDEX IF NOT EXISTS idx_skill_created_by_public
    ON skill (created_by) WHERE status = 'ACTIVE' AND hidden = FALSE;

-- ---------------------------------------------------------------------------
-- 5. Seed level-1 categories (the six existing wire values, one-to-one)
-- ---------------------------------------------------------------------------

INSERT INTO skill_category (level, slug, legacy_domain, sort_order, enabled, created_by)
VALUES
    (1, 'design',             'DESIGN',             10, TRUE, NULL),
    (1, 'development',        'DEVELOPMENT',        20, TRUE, NULL),
    (1, 'testing',            'TESTING',            30, TRUE, NULL),
    (1, 'engineering',        'ENGINEERING',        40, TRUE, NULL),
    (1, 'project-management', 'PROJECT_MANAGEMENT', 50, TRUE, NULL),
    (1, 'other',              'OTHER',              60, TRUE, NULL)
ON CONFLICT DO NOTHING;

-- Level-2 rows are intentionally NOT seeded: the specification makes category administrators the
-- only creator of second-level categories, so the tree starts with level-1 only.

INSERT INTO skill_category_translation (category_id, locale, display_name)
SELECT c.id, v.locale, v.display_name
FROM skill_category c
JOIN (VALUES
    ('design',             'zh', '设计'),
    ('design',             'en', 'Design'),
    ('design',             'ko', '디자인'),
    ('design',             'ru', 'Дизайн'),
    ('development',        'zh', '开发'),
    ('development',        'en', 'Development'),
    ('development',        'ko', '개발'),
    ('development',        'ru', 'Разработка'),
    ('testing',            'zh', '测试'),
    ('testing',            'en', 'Testing'),
    ('testing',            'ko', '테스트'),
    ('testing',            'ru', 'Тестирование'),
    ('engineering',        'zh', '工程'),
    ('engineering',        'en', 'Engineering'),
    ('engineering',        'ko', '엔지니어링'),
    ('engineering',        'ru', 'Инженерия'),
    ('project-management', 'zh', '项目管理'),
    ('project-management', 'en', 'Project Management'),
    ('project-management', 'ko', '프로젝트 관리'),
    ('project-management', 'ru', 'Управление проектами'),
    ('other',              'zh', '其他'),
    ('other',              'en', 'Other'),
    ('other',              'ko', '기타'),
    ('other',              'ru', 'Другое')
) AS v(slug, locale, display_name) ON v.slug = c.slug
ON CONFLICT (category_id, locale) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 6. Backfill
-- ---------------------------------------------------------------------------

-- Creator: fall back to the current owner so the "created by" filter has something to match for
-- rows that predate the column. Non-destructive and idempotent.
UPDATE skill SET created_by = owner_id WHERE created_by IS NULL;

-- Category: one-to-one from the legacy wire value. No row should be guessed or dropped.
UPDATE skill s
SET category_id = c.id
FROM skill_category c
WHERE c.legacy_domain = s.domain
  AND s.category_id IS NULL;

-- V121 normalised everything to OTHER, but a row written by an older instance between the two
-- migrations can still carry NULL. Map it to the same honest "other" bucket rather than guessing.
UPDATE skill s
SET category_id = c.id
FROM skill_category c
WHERE s.domain IS NULL
  AND s.category_id IS NULL
  AND c.legacy_domain = 'OTHER';

-- Every skill landed on a category. Fail loudly instead of leaving NULLs that would silently
-- disappear from every category filter later.
DO $$
DECLARE
    missing BIGINT;
BEGIN
    SELECT COUNT(*) INTO missing FROM skill WHERE category_id IS NULL;
    IF missing > 0 THEN
        RAISE EXCEPTION 'skill taxonomy backfill left % skills without a category', missing;
    END IF;
END
$$;
