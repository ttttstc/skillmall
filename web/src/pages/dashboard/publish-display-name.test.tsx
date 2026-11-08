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

function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><PublishPage /></QueryClientProvider>)
  return client
}
function select(name: string) {
  fireEvent.change(screen.getByLabelText('package'), { target: { files: [new File(['zip'], name)] } })
}
function advance(times: number) {
  for (let index = 0; index < times; index += 1) {
    fireEvent.click(screen.getByRole('button', { name: 'publish.wizardNext' }))
  }
}
function goToStep(step: 1 | 2 | 3) {
  advance(step - 1)
}
function backToStepOne(times: number) {
  for (let index = 0; index < times; index += 1) {
    fireEvent.click(screen.getByRole('button', { name: 'publish.wizardPrevious' }))
  }
}
const metadata = (name: string) => ({ name: 'stable-slug', displayName: name, version: '1.0.0', description: 'summary' })

afterEach(cleanup)
beforeEach(() => { preview.mockReset(); publish.mockReset(); publish.mockResolvedValue({ namespace: 'team-ai', slug: 'stable-slug', version: '1.0.0', status: 'PENDING_REVIEW' }) })

describe('web display name publishing', () => {
  it('prefills from the package and sends the explicit edited name with the original file', async () => {
    preview.mockResolvedValue(metadata('包内名称'))
    mount(); select('one.zip'); goToStep(3)
    await waitFor(() => expect((screen.getByLabelText('publish.displayName') as HTMLInputElement).value).toBe('包内名称'))
    fireEvent.change(screen.getByLabelText('publish.displayName'), { target: { value: '网页名称' } })
    fireEvent.click(screen.getByRole('button', { name: 'publish.confirm' }))
    await waitFor(() => expect(publish).toHaveBeenCalledWith(expect.objectContaining({ displayName: '网页名称', file: expect.objectContaining({ name: 'one.zip' }) })))
  })

  it('keeps user input when a replacement package finishes parsing', async () => {
    preview.mockResolvedValue(metadata('初始建议'))
    mount(); select('one.zip'); goToStep(3)
    await waitFor(() => expect((screen.getByLabelText('publish.displayName') as HTMLInputElement).value).toBe('初始建议'))
    fireEvent.change(screen.getByLabelText('publish.displayName'), { target: { value: '我填写的名称' } })
    backToStepOne(2); preview.mockResolvedValue(metadata('另一个建议')); select('two.zip'); advance(2)
    await waitFor(() => expect(preview).toHaveBeenCalledTimes(2))
    expect((screen.getByLabelText('publish.displayName') as HTMLInputElement).value).toBe('我填写的名称')
  })

  it('ignores a stale package response and blocks submit until the current preview succeeds', async () => {
    let finishOld!: (value: ReturnType<typeof metadata>) => void
    preview.mockReturnValueOnce(new Promise(resolve => { finishOld = resolve })).mockResolvedValueOnce(metadata('当前包名称'))
    mount(); select('old.zip'); goToStep(3)
    expect(screen.getByRole('button', { name: 'publish.confirm' }).hasAttribute('disabled')).toBe(true)
    await waitFor(() => expect(preview).toHaveBeenCalledTimes(1))
    backToStepOne(2); select('new.zip'); advance(2)
    await waitFor(() => expect((screen.getByLabelText('publish.displayName') as HTMLInputElement).value).toBe('当前包名称'))
    finishOld(metadata('过期包名称'))
    await new Promise(resolve => setTimeout(resolve, 20))
    expect((screen.getByLabelText('publish.displayName') as HTMLInputElement).value).toBe('当前包名称')
  })

  it('shows a retry and cannot publish a package with invalid metadata', async () => {
    preview.mockRejectedValueOnce(new Error('invalid package')).mockResolvedValueOnce(metadata('重试后的名称'))
    mount(); select('invalid.zip'); goToStep(2)
    await screen.findByRole('alert')
    fireEvent.click(screen.getByRole('button', { name: 'publish.retryPackagePreview' }))
    advance(1)
    await waitFor(() => expect((screen.getByLabelText('publish.displayName') as HTMLInputElement).value).toBe('重试后的名称'))
    expect(screen.getByRole('button', { name: 'publish.confirm' }).hasAttribute('disabled')).toBe(false)
  })
})


describe('examples preflight remains non-blocking', () => {
  for (const status of ['ready', 'none', 'invalid'] as const) {
    it(`shows ${status} recognition and still allows publishing`, async () => {
      preview.mockResolvedValue({ ...metadata('案例技能'), examplesStatus: status, ...(status === 'invalid' ? {} : { examplesCount: status === 'ready' ? 2 : 0 }) })
      mount(); select('examples.zip'); goToStep(2)
      await screen.findByText(`publish.examples${status[0].toUpperCase()}${status.slice(1)}`)
      advance(1)
      expect(screen.getByRole('button', { name: 'publish.confirm' }).hasAttribute('disabled')).toBe(false)
    })
  }
  it('tolerates old preview responses without examples fields', async () => {
    preview.mockResolvedValue(metadata('旧技能'))
    mount(); select('old.zip'); goToStep(2)
    expect(screen.queryByText(/publish.examples/)).toBeNull()
    advance(1)
    await waitFor(() => expect((screen.getByLabelText('publish.displayName') as HTMLInputElement).value).toBe('旧技能'))
    expect(screen.getByRole('button', { name: 'publish.confirm' }).hasAttribute('disabled')).toBe(false)
  })
})
