// @vitest-environment jsdom
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { InstallGuide } from './install-guide'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    // Keep the key visible and append interpolated values so assertions can prove which
    // values the guide passes to each message.
    t: (key: string, values?: Record<string, string>) => {
      const pairs = values ? Object.entries(values).map(([name, value]) => `${name}=${value}`) : []
      return pairs.length > 0 ? `${key}[${pairs.join(',')}]` : key
    },
  }),
}))

function withRuntimeConfig(appBaseUrl: string) {
  Reflect.set(window, '__SKILLHUB_RUNTIME_CONFIG__', { appBaseUrl })
}

afterEach(() => {
  cleanup()
  Reflect.deleteProperty(window, '__SKILLHUB_RUNTIME_CONFIG__')
})

describe('InstallGuide', () => {
  it('offers the CLI command for the current market with the full coordinate and pinned version', () => {
    withRuntimeConfig('https://market.example.com')

    const html = renderToStaticMarkup(createElement(InstallGuide, {
      namespace: 'team-alpha',
      slug: 'demo-skill',
      version: '1.2.3',
      installable: true,
      canDownload: true,
      onDownload: () => {},
    }))

    expect(html).toContain('skillDetail.installCliTitle')
    expect(html).toContain('npx @astron-team/skillhub@latest install @team-alpha/demo-skill --version 1.2.3 --registry https://market.example.com')
  })

  it('keeps a documented manual install path with the skill slug and version', () => {
    withRuntimeConfig('https://market.example.com')

    const html = renderToStaticMarkup(createElement(InstallGuide, {
      namespace: 'global',
      slug: 'demo-skill',
      version: '1.2.3',
      installable: true,
      canDownload: true,
      onDownload: () => {},
    }))

    expect(html).toContain('skillDetail.installManualTitle')
    expect(html).toContain('skillDetail.installManualStepExtract[slug=demo-skill]')
    expect(html).toContain('skillDetail.installManualDownload[version=1.2.3]')
    // Manual installation never claims to leave a CLI-managed installation behind.
    expect(html).toContain('skillDetail.installManualMetadataNote')
    expect(html).toContain('skillDetail.installConflictTitle')
  })

  it('explains that a non-distributable version cannot be installed instead of showing a command', () => {
    withRuntimeConfig('https://market.example.com')

    const html = renderToStaticMarkup(createElement(InstallGuide, {
      namespace: 'global',
      slug: 'demo-skill',
      version: '1.2.3',
      installable: false,
      canDownload: false,
      onDownload: () => {},
    }))

    expect(html).toContain('skillDetail.installNotDistributable')
    expect(html).not.toContain('npx @astron-team/skillhub')
    expect(html).not.toContain('skillDetail.installManualStepExtract')
  })

  it('disables the manual download action when the visitor may not fetch the package', () => {
    withRuntimeConfig('https://market.example.com')

    render(createElement(InstallGuide, {
      namespace: 'global',
      slug: 'demo-skill',
      version: '1.2.3',
      installable: true,
      canDownload: false,
      onDownload: () => {},
    }))

    expect((screen.getByRole('button', { name: 'skillDetail.installManualDownload[version=1.2.3]' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('runs the download handler from the manual install action', () => {
    withRuntimeConfig('https://market.example.com')
    const onDownload = vi.fn()

    render(createElement(InstallGuide, {
      namespace: 'global',
      slug: 'demo-skill',
      version: '1.2.3',
      installable: true,
      canDownload: true,
      onDownload,
    }))

    fireEvent.click(screen.getByRole('button', { name: 'skillDetail.installManualDownload[version=1.2.3]' }))
    expect(onDownload).toHaveBeenCalledTimes(1)
  })
})
