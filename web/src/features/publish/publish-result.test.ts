import { describe, expect, it } from 'vitest'
import { publishNoticeCopyKeys, publishResultCopyKeys } from './publish-result'

describe('publish result copy', () => {
  it('describes a direct publish as published, never as submitted for review', () => {
    const keys = publishResultCopyKeys('PUBLISHED')

    expect(keys.title).toBe('publish.publishedTitle')
    expect(keys.description).toBe('publish.publishedDescription')
    expect(`${keys.title} ${keys.description}`).not.toMatch(/review|pending/i)
  })

  it('describes a private upload as uploaded and waiting for confirmation, not for approval', () => {
    const keys = publishResultCopyKeys('UPLOADED')

    expect(keys.title).toBe('publish.privateUploadedTitle')
    expect(keys.description).toBe('publish.privateUploadedDescription')
    expect(`${keys.title} ${keys.description}`).not.toMatch(/review|pending/i)
  })

  it.each(['PUBLIC', 'NAMESPACE_ONLY', ''])(
    'explains direct publication for visibility %s',
    (visibility) => {
      const keys = publishNoticeCopyKeys(visibility)

      expect(keys.title).toBe('publish.directPublishNotice.title')
      expect(keys.description).toBe('publish.directPublishNotice.description')
      expect(`${keys.title} ${keys.description}`).not.toMatch(/reviewNotice/i)
    },
  )

  it('explains the private upload path for PRIVATE', () => {
    const keys = publishNoticeCopyKeys('PRIVATE')

    expect(keys.title).toBe('publish.privateUploadNotice.title')
    expect(keys.description).toBe('publish.privateUploadNotice.description')
    expect(`${keys.title} ${keys.description}`).not.toMatch(/reviewNotice/i)
  })
})
