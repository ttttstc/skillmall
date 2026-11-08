import { describe, expect, it } from 'vitest'
import indexHtml from '../../index.html?raw'
import en from './locales/en.json'
import ko from './locales/ko.json'
import ru from './locales/ru.json'
import zh from './locales/zh.json'

/**
 * Market branding for issue #10.
 *
 * The market product is SkillMall. The CLI, its npm package, the `skillhub://` protocol, the
 * `.skillhub` data directory and the upstream open-source project keep their real SkillHub names.
 * These assertions exist so a later "brand sweep" cannot rename those facts.
 */

const locales = { zh, en, ko, ru } as const

const MARKET_BRAND_KEYS = [
  'login.title',
  'footer.copyright',
  'landing.footerLicense',
  'publish.subtitle',
  'authShell.capabilities',
  'authShell.eyebrow',
  'home.quickStart.description',
  'githubCatalog.description',
  // The quick-start and registry copy describes this market's own registry, so it follows
  // the market brand; only the CLI package and data-directory names stay SkillHub.
  'landing.quickStart.agent.description',
  'landing.experience.quickStart.agent.instruction',
  'landing.experience.quickStart.agent.instructionSuffix',
  'landing.experience.quickStart.agent.discovery.searchTitle',
] as const

function readPath(source: unknown, dottedKey: string): unknown {
  return dottedKey.split('.').reduce<unknown>((node, segment) => {
    if (node && typeof node === 'object') return (node as Record<string, unknown>)[segment]
    return undefined
  }, source)
}

describe('market brand in the document head', () => {
  it('titles the browser tab after the market', () => {
    expect(indexHtml).toContain('<title>SkillMall</title>')
    expect(indexHtml).not.toContain('<title>SkillHub</title>')
  })
})

describe('market brand in the four frontend locales', () => {
  for (const [language, messages] of Object.entries(locales)) {
    it(`names the market SkillMall in ${language}`, () => {
      for (const key of MARKET_BRAND_KEYS) {
        const value = readPath(messages, key)
        expect(typeof value, `${language}:${key} must be a string`).toBe('string')
        expect(value, `${language}:${key} must name the market`).toContain('SkillMall')
        expect(value, `${language}:${key} must not call the market SkillHub`).not.toContain('SkillHub')
      }
    })

    it(`keeps the CLI, package and data-directory facts as SkillHub in ${language}`, () => {
      // Real artifact names: the npm package, the CLI display name and the CLI metadata path.
      expect(readPath(messages, 'landing.quickStart.cli.command')).toBe('npm i -g @astron-team/skillhub')
      expect(readPath(messages, 'landing.quickStart.human.commandTemplate')).toContain(
        'npx @astron-team/skillhub@latest',
      )
      expect(readPath(messages, 'skillDetail.installMethodSkillhub')).toBe('SkillHub CLI')
      expect(readPath(messages, 'skillDetail.installManualMetadataNote')).toContain('.skillhub/metadata.json')
      expect(readPath(messages, 'skillDetail.installCliTitle')).toContain('SkillHub CLI')
    })
  }
})
