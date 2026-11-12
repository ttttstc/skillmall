import { fetchJson, getCsrfHeaders, WEB_API_PREFIX } from '@/api/client'
import type {
  GitcodeImportRequest,
  GitcodeImportResult,
  GitcodeInspectRequest,
  GitcodeInspectResult,
} from './types'

/**
 * Skill surface shared with the publish form.
 *
 * The design contract (§4.3) spells the endpoints as `/api/v1/skills/...`; the web app reaches the
 * same skill surface through the `/api/web` BFF prefix (`previewSkillPackage` does the same), so the
 * backend has to mount both, or the only change needed here is this one constant.
 */
const SKILL_API_BASE = `${WEB_API_PREFIX}/skills`

/** Repository traversal has a 60 s server budget; keep the client above it plus publish time. */
const INSPECT_TIMEOUT_MS = 90_000
const IMPORT_TIMEOUT_MS = 150_000

function cleanNamespace(namespace: string): string {
  return namespace.startsWith('@') ? namespace.slice(1) : namespace
}

/** Discover the skill directories of a repository, or preview one of them when `path` is set. */
export function inspectGitcodeRepository(request: GitcodeInspectRequest): Promise<GitcodeInspectResult> {
  return fetchJson<GitcodeInspectResult>(`${SKILL_API_BASE}/gitcode/inspect`, {
    method: 'POST',
    headers: getCsrfHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(request),
    timeoutMs: INSPECT_TIMEOUT_MS,
  })
}

/** Import one skill directory through the existing publish pipeline. */
export function importGitcodeSkill(namespace: string, request: GitcodeImportRequest): Promise<GitcodeImportResult> {
  return fetchJson<GitcodeImportResult>(
    `${SKILL_API_BASE}/${encodeURIComponent(cleanNamespace(namespace))}/import/gitcode`,
    {
      method: 'POST',
      headers: getCsrfHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(request),
      timeoutMs: IMPORT_TIMEOUT_MS,
    }
  )
}
