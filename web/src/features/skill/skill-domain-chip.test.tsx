// @vitest-environment jsdom

import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SkillCard } from './skill-card'
import { SkillListRow } from './skill-list-row'

vi.mock('@/features/auth/use-auth', () => ({ useAuth: () => ({ isAuthenticated: false }) }))
vi.mock('@/features/social/use-star', () => ({ useStarredIdSet: () => ({ starredIds: new Set<number>() }) }))
vi.mock('react-i18next', async (importOriginal) => ({
  ...await importOriginal<typeof import('react-i18next')>(),
  useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'en' } }),
}))

const baseSkill = {
  id: 7,
  slug: 'workflow-helper',
  displayName: 'Workflow Helper',
  summary: 'Automates a repeatable workflow.',
  namespace: 'team-a',
  downloadCount: 1200,
  starCount: 12,
  ratingCount: 0,
  updatedAt: '2026-10-26T00:00:00Z',
  canSubmitPromotion: false,
}

afterEach(cleanup)

/**
 * The category chip is the same fact on both listing views, so both are checked here:
 * the grid card and the single row. `t` returns the key, which makes the chip text
 * itself the assertion that the right locale key was used.
 */
describe('skill domain chip', () => {
  it('shows the category on the card when the summary carries one', () => {
    render(<SkillCard skill={{ ...baseSkill, domain: 'DESIGN' }} />)

    expect(screen.getByText('home.domain.DESIGN')).toBeTruthy()
  })

  it('shows the category on the row when the summary carries one', () => {
    render(<SkillListRow skill={{ ...baseSkill, domain: 'TESTING' }} />)

    expect(screen.getByText('home.domain.TESTING')).toBeTruthy()
  })

  it('leaves the chip out when the summary has no category', () => {
    render(<SkillCard skill={baseSkill} />)
    render(<SkillListRow skill={baseSkill} />)

    expect(screen.queryByText(/^home\.domain\./u)).toBeNull()
  })
})
