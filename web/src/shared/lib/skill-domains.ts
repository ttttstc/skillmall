/**
 * The six fixed first-level marketplace categories (GitHub issue #5).
 *
 * The list is a closed set shared with the backend enum `SkillDomain`; it is deliberately not
 * configurable at runtime, so a category always has a translation, an index and a filter chip.
 */
export const SKILL_DOMAINS = [
  'DESIGN',
  'DEVELOPMENT',
  'TESTING',
  'ENGINEERING',
  'PROJECT_MANAGEMENT',
  'OTHER',
] as const

export type SkillDomain = (typeof SKILL_DOMAINS)[number]

export function isSkillDomain(value: unknown): value is SkillDomain {
  return typeof value === 'string' && (SKILL_DOMAINS as readonly string[]).includes(value)
}

/**
 * Slugs of the six first-level categories seeded by the taxonomy migration.
 *
 * Only level 1 is a constant: second-level categories are created by category administrators at
 * runtime, so the client must never validate those against a local list — an unknown second-level
 * slug is passed through and the server answers 400.
 */
export const SKILL_CATEGORY_SLUGS = [
  'design',
  'development',
  'testing',
  'engineering',
  'project-management',
  'other',
] as const

export type SkillCategorySlug = (typeof SKILL_CATEGORY_SLUGS)[number]

export function isSkillCategorySlug(value: unknown): value is SkillCategorySlug {
  return typeof value === 'string' && (SKILL_CATEGORY_SLUGS as readonly string[]).includes(value)
}

/** Legacy `domain` wire value → the equivalent first-level category slug. */
export const DOMAIN_TO_CATEGORY_SLUG: Record<SkillDomain, SkillCategorySlug> = {
  DESIGN: 'design',
  DEVELOPMENT: 'development',
  TESTING: 'testing',
  ENGINEERING: 'engineering',
  PROJECT_MANAGEMENT: 'project-management',
  OTHER: 'other',
}

export function domainToCategorySlug(domain: SkillDomain | undefined): SkillCategorySlug | undefined {
  return domain ? DOMAIN_TO_CATEGORY_SLUG[domain] : undefined
}
