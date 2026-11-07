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
