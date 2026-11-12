/** Host the importer is allowed to read (design §3: api/raw/网页域名白名单). */
const GITCODE_HOSTS = new Set(['gitcode.com', 'www.gitcode.com'])

export type GitcodeUrlIssue = 'empty' | 'format' | 'host'

export interface GitcodeUrlParse {
  ok: boolean
  issue?: GitcodeUrlIssue
  /** Owner segment of `/owner/repo`. */
  owner?: string
  repository?: string
  /** Ref taken from a `/tree/{ref}/...` deep link, when the user pasted one. */
  ref?: string
  /** Directory hint from a `/tree/{ref}/{path}` deep link. */
  path?: string
}

/**
 * Basic front-end validation of `https://gitcode.com/{owner}/{repo}[.git][/tree/{ref}[/{path}]]`.
 *
 * The backend owns the authoritative parsing; this only rejects what can never work (wrong host,
 * missing owner/repository) so step 1 fails fast, and it recovers the `ref`/`path` of a deep link
 * so the wizard can land on the directory the user linked to.
 */
export function parseGitcodeRepoUrl(raw: string): GitcodeUrlParse {
  const trimmed = raw.trim()
  if (!trimmed) {
    return { ok: false, issue: 'empty' }
  }

  let url: URL
  try {
    url = new URL(trimmed)
  } catch {
    return { ok: false, issue: 'format' }
  }

  if (url.protocol !== 'https:' && url.protocol !== 'http:') {
    return { ok: false, issue: 'format' }
  }

  const host = url.hostname.toLowerCase()
  if (!GITCODE_HOSTS.has(host)) {
    return { ok: false, issue: 'host' }
  }

  const segments = url.pathname.split('/').filter(Boolean)
  if (segments.length < 2) {
    return { ok: false, issue: 'format' }
  }

  const owner = segments[0]
  const repository = segments[1].replace(/\.git$/i, '')
  if (!owner || !repository) {
    return { ok: false, issue: 'format' }
  }

  // `/owner/repo/tree/{ref}/{path...}` — the ref may itself contain slashes, so only the part
  // after `tree/` is recoverable; treat the whole remaining tail as a path hint when it resolves.
  const treeIndex = segments.indexOf('tree')
  const ref = treeIndex > 1 && segments[treeIndex + 1] ? decodeURIComponent(segments[treeIndex + 1]) : undefined
  const path = treeIndex > 1 && segments.length > treeIndex + 2
    ? segments.slice(treeIndex + 2).map(decodeURIComponent).join('/')
    : undefined

  return { ok: true, owner, repository, ref, path }
}

/** Copy key per validation issue; all fall back to the generic repository error message. */
export function gitcodeUrlIssueKeys(issue: GitcodeUrlIssue): { key: string; fallbackKey: string } {
  switch (issue) {
    case 'empty':
      return { key: 'publish.repoUrlRequired', fallbackKey: 'publish.repoInspectFailed' }
    case 'host':
      return { key: 'publish.repoUrlHost', fallbackKey: 'publish.repoInspectFailed' }
    case 'format':
      return { key: 'publish.repoUrlInvalid', fallbackKey: 'publish.repoInspectFailed' }
  }
}
