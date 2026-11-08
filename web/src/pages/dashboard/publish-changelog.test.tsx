/** @vitest-environment jsdom */
import { fireEvent, render, screen, waitFor, cleanup } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const preview = vi.hoisted(() => vi.fn())
const publish = vi.hoisted(() => vi.fn())
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => vi.fn(), useSearch: () => ({ namespace: 'team-ai' }) }))
vi.mock('react-i18next', async () => ({ ...await vi.importActual<typeof import('react-i18next')>('react-i18next'), useTranslation: () => ({ t: (key: string) => key }) }))
vi.mock('@/shared/hooks/use-skill-queries', () => ({ useSkillDetail: () => ({ data: undefined }), previewSkillPackage: preview, usePublishSkill: () => ({ mutateAsync: publish, isPending: false }), useSkillDomain: () => ({ data: undefined, isLoading: false }), useChangeSkillDomain: () => ({ mutateAsync: vi.fn(), isPending: false }) }))
vi.mock('@/shared/hooks/use-namespace-queries', () => ({ useMyNamespaces: () => ({ data: [{ id: 1, slug: 'team-ai', displayName: 'Team' }], isLoading: false }) }))
vi.mock('@/features/publish/upload-zone', () => ({ UploadZone: ({ onFileSelect }: { onFileSelect: (file: File) => void }) => <input aria-label="package" type="file" onChange={e => { if (e.target.files?.[0]) onFileSelect(e.target.files[0]) }} /> }))
vi.mock('@/features/skill/skill-avatar-editor', () => ({ KEEP_AVATAR: { action: 'KEEP', ready: true }, SkillAvatarEditor: () => null }))
vi.mock('@/features/skill/skill-avatar-manager', () => ({ SkillAvatarManager: () => null }))
vi.mock('@/shared/lib/navigate-after-overlays', () => ({ navigateAfterOverlays: (action: () => void) => action() }))
vi.mock('@/shared/lib/toast', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))
import { PublishPage } from './publish'

const metadata = () => ({ name: 'stable-slug', displayName: 'Stable', version: '2.0.0', description: 'summary' })

function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><PublishPage /></QueryClientProvider>)
}
function select(name: string) {
  fireEvent.change(screen.getByLabelText('package'), { target: { files: [new File(['zip'], name)] } })
}
function advanceToBasicInfo(times: number) {
  for (let index = 0; index < times; index += 1) {
    fireEvent.click(screen.getByRole('button', { name: 'publish.wizardNext' }))
  }
}
async function waitForSubmittable() {
  const confirm = screen.getByRole('button', { name: 'publish.confirm' })
  await waitFor(() => expect(confirm.hasAttribute('disabled')).toBe(false))
}

afterEach(cleanup)
beforeEach(() => {
  preview.mockReset()
  publish.mockReset()
  publish.mockResolvedValue({ namespace: 'team-ai', slug: 'stable-slug', version: '2.0.0', status: 'PUBLISHED' })
})

describe('version change note publishing', () => {
  it('keeps the package step blocking until a package is selected', () => {
    mount()

    expect(screen.getByRole('button', { name: 'publish.wizardNext' }).hasAttribute('disabled')).toBe(true)
  })

  it('sends the typed change note with the new version', async () => {
    preview.mockResolvedValue(metadata())
    mount()
    select('next.zip'); advanceToBasicInfo(2)

    const note = screen.getByLabelText('publish.changelog') as HTMLTextAreaElement
    await waitForSubmittable()
    expect(note.disabled).toBe(false)
    fireEvent.change(note, { target: { value: '补充安装说明' } })
    fireEvent.click(screen.getByRole('button', { name: 'publish.confirm' }))

    await waitFor(() => expect(publish).toHaveBeenCalledWith(
      expect.objectContaining({ changelog: '补充安装说明', file: expect.objectContaining({ name: 'next.zip' }) }),
    ))
  })

  it('sends an empty note when the author leaves the field blank', async () => {
    preview.mockResolvedValue(metadata())
    mount()
    select('next.zip'); advanceToBasicInfo(2)

    await waitForSubmittable()
    fireEvent.click(screen.getByRole('button', { name: 'publish.confirm' }))

    await waitFor(() => expect(publish).toHaveBeenCalledWith(expect.objectContaining({ changelog: '' })))
  })
})
