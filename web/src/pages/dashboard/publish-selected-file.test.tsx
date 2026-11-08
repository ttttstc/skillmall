/** @vitest-environment jsdom */
import { fireEvent, render, screen, cleanup } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const preview = vi.hoisted(() => vi.fn())
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => vi.fn(), useSearch: () => ({ namespace: 'team-ai' }) }))
vi.mock('react-i18next', async () => ({ ...await vi.importActual<typeof import('react-i18next')>('react-i18next'), useTranslation: () => ({ t: (key: string) => key }) }))
vi.mock('@/shared/hooks/use-skill-queries', () => ({ useSkillDetail: () => ({ data: undefined }), previewSkillPackage: preview, usePublishSkill: () => ({ mutateAsync: vi.fn(), isPending: false }), useSkillDomain: () => ({ data: undefined, isLoading: false }), useChangeSkillDomain: () => ({ mutateAsync: vi.fn(), isPending: false }) }))
vi.mock('@/shared/hooks/use-namespace-queries', () => ({ useMyNamespaces: () => ({ data: [{ id: 1, slug: 'team-ai', displayName: 'Team' }], isLoading: false }) }))
vi.mock('@/features/publish/upload-zone', () => ({ UploadZone: ({ onFileSelect }: { onFileSelect: (file: File) => void }) => <input aria-label="package" type="file" onChange={e => { if (e.target.files?.[0]) onFileSelect(e.target.files[0]) }} /> }))
vi.mock('@/features/skill/skill-avatar-editor', () => ({ KEEP_AVATAR: { action: 'KEEP', ready: true }, SkillAvatarEditor: () => null }))
vi.mock('@/features/skill/skill-avatar-manager', () => ({ SkillAvatarManager: () => null }))
vi.mock('@/shared/lib/navigate-after-overlays', () => ({ navigateAfterOverlays: (action: () => void) => action() }))
vi.mock('@/shared/lib/toast', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))
import { PublishPage } from './publish'

function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><PublishPage /></QueryClientProvider>)
}

function select(name: string) {
  fireEvent.change(screen.getByLabelText('package'), { target: { files: [new File(['zip'], name)] } })
}

afterEach(cleanup)
beforeEach(() => {
  preview.mockReset()
  preview.mockResolvedValue({ name: 'stable-slug', displayName: 'Stable', version: '1.0.0', description: 'summary' })
})

describe('publish selected package strip', () => {
  it('shows the selected package with download and remove actions', () => {
    mount()
    select('skill.zip')

    // The UCD flow keeps the chosen package visible in a tinted strip with both actions,
    // instead of only a remove button inside the drop zone.
    expect(screen.getByText(/skill\.zip/u)).not.toBeNull()
    expect(screen.getByRole('button', { name: /publish\.downloadSelectedFile/u })).not.toBeNull()
    expect(screen.getByRole('button', { name: /publish\.removeSelectedFile/u })).not.toBeNull()
  })

  it('downloads the selected file through a temporary object URL', () => {
    const createObjectURL = vi.fn(() => 'blob:selected')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)

    mount()
    select('skill.zip')
    fireEvent.click(screen.getByRole('button', { name: /publish\.downloadSelectedFile/u }))

    expect(createObjectURL).toHaveBeenCalledTimes(1)
    expect(click).toHaveBeenCalledTimes(1)
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:selected')
    click.mockRestore()
  })

  it('clears the strip and blocks the wizard again after removing the file', () => {
    mount()
    select('skill.zip')
    fireEvent.click(screen.getByRole('button', { name: /publish\.removeSelectedFile/u }))

    expect(screen.queryByRole('button', { name: /publish\.downloadSelectedFile/u })).toBeNull()
    expect(screen.getByRole('button', { name: 'publish.wizardNext' }).hasAttribute('disabled')).toBe(true)
  })
})