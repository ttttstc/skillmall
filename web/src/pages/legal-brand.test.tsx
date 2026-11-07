import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'

/**
 * /terms and /privacy are reachable from the footer, so they are market pages: the service they
 * describe is this market. Their text must name SkillMall while leaving upstream attribution
 * (Apache License 2.0, the open-source project) intact.
 */

const languageFixture = vi.hoisted(() => ({ resolvedLanguage: 'zh-CN' }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ i18n: { resolvedLanguage: languageFixture.resolvedLanguage } }),
}))

import { PrivacyPolicyPage } from './privacy'
import { TermsOfServicePage } from './terms'

function renderWithLanguage(language: string, render: () => React.ReactElement) {
  languageFixture.resolvedLanguage = language
  return renderToStaticMarkup(render())
}

describe('legal pages brand the market as SkillMall', () => {
  it('presents the terms of service under the SkillMall market name', () => {
    for (const language of ['zh-CN', 'en-US', 'ko-KR']) {
      const html = renderWithLanguage(language, () => <TermsOfServicePage />)

      expect(html, `${language} terms`).toContain('SkillMall')
      expect(html, `${language} terms`).not.toContain('SkillHub')
      // Attribution stays factual: the license of the upstream project is still stated.
      expect(html, `${language} terms`).toContain('Apache License 2.0')
    }
  })

  it('presents the privacy policy under the SkillMall market name', () => {
    for (const language of ['zh-CN', 'en-US', 'ko-KR']) {
      const html = renderWithLanguage(language, () => <PrivacyPolicyPage />)

      expect(html, `${language} privacy`).toContain('SkillMall')
      expect(html, `${language} privacy`).not.toContain('SkillHub')
    }
  })
})
