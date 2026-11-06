// @vitest-environment jsdom

import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SecurityEvaluationNotice } from './security-evaluation-notice'

vi.mock('react-i18next', async (importOriginal) => ({
  ...await importOriginal<typeof import('react-i18next')>(),
  useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'en' } }),
}))

afterEach(cleanup)

describe('SecurityEvaluationNotice', () => {
  it('states the not-evaluated state instead of a passing result', () => {
    render(<SecurityEvaluationNotice />)

    expect(screen.getByText('securityAudit.notEvaluatedTitle')).toBeTruthy()
    expect(screen.getByText('securityAudit.notEvaluatedDescription')).toBeTruthy()
  })

  it('never renders a qualitative security claim', () => {
    const { container } = render(<SecurityEvaluationNotice />)
    const text = container.textContent ?? ''

    for (const claim of ['securityAudit.verdict.SAFE', 'securityAudit.verdict.SUSPICIOUS', 'securityAudit.verdict.DANGEROUS', 'securityAudit.verdict.BLOCKED']) {
      expect(text).not.toContain(claim)
    }
    expect(container.querySelector('[data-analytics-id]')).toBeNull()
  })
})
