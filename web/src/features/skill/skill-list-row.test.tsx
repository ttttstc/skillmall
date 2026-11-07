// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
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

describe('SkillListRow', () => {
  it('renders the name, coordinate and summary of one skill', () => {
    render(<SkillListRow skill={baseSkill} />)

    expect(screen.getByText('Workflow Helper')).toBeTruthy()
    expect(screen.getByText('@team-a')).toBeTruthy()
    expect(screen.getByText('Automates a repeatable workflow.')).toBeTruthy()
  })

  it('exposes the same facts as the card in one row', () => {
    const skill = {
      ...baseSkill,
      headlineVersion: { id: 1, version: '1.2.0', status: 'PUBLISHED' },
    }
    render(<SkillListRow skill={skill} />)

    expect(screen.getByText('v1.2.0')).toBeTruthy()
    expect(screen.getByText('1.2K')).toBeTruthy()
    expect(screen.getByText('12')).toBeTruthy()
  })

  it('activates on click, Enter and Space only when interactive', () => {
    const onClick = vi.fn()
    render(<SkillListRow skill={baseSkill} onClick={onClick} />)

    const row = screen.getByRole('link')
    fireEvent.click(row)
    fireEvent.keyDown(row, { key: 'Enter' })
    fireEvent.keyDown(row, { key: ' ' })
    fireEvent.keyDown(row, { key: 'ArrowDown' })

    expect(onClick).toHaveBeenCalledTimes(3)
  })

  it('stays a passive row without a click handler', () => {
    render(<SkillListRow skill={baseSkill} />)

    expect(screen.queryByRole('link')).toBeNull()
  })
})
