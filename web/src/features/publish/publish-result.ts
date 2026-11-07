/** Translation keys describing the outcome of one publish action. */
export interface PublishCopyKeys {
  title: string
  description: string
}

/**
 * Copy shown after a publish request.
 *
 * A `PUBLIC` / `NAMESPACE_ONLY` upload is published directly, so it must never be
 * described as "submitted for review". The remaining non-published outcome is a
 * `PRIVATE` upload, which stays hidden until the owner confirms it from the detail page.
 */
export function publishResultCopyKeys(status: string): PublishCopyKeys {
  return status === 'PUBLISHED'
    ? { title: 'publish.publishedTitle', description: 'publish.publishedDescription' }
    : { title: 'publish.privateUploadedTitle', description: 'publish.privateUploadedDescription' }
}

/**
 * Copy for the notice above the upload form: it states what the selected visibility does
 * with the package, because the visibility selector is the only thing that changes the
 * publication outcome on this page.
 */
export function publishNoticeCopyKeys(visibility: string): PublishCopyKeys {
  return visibility === 'PRIVATE'
    ? { title: 'publish.privateUploadNotice.title', description: 'publish.privateUploadNotice.description' }
    : { title: 'publish.directPublishNotice.title', description: 'publish.directPublishNotice.description' }
}
