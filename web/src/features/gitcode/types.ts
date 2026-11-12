import type { PublishResult } from '@/api/types'

/**
 * Wire shapes for the GitCode import contract (openspec GITCODE-IMPORT-DESIGN.md §4.3).
 *
 * `POST /skills/gitcode/inspect` serves both wizard steps: without `path` it only discovers the
 * skill directories of the repository, with `path` it also returns that skill's file list and
 * SKILL.md body, which the preview screen renders. The two extra fields are optional so a
 * backend that has not implemented the `path`-scoped form still answers with the discovery shape.
 */
export type GitcodeRefType = 'BRANCH' | 'TAG' | 'COMMIT'

export interface GitcodeInspectRequest {
  repoUrl: string
  ref?: string
  path?: string
}

/** One path inside the selected skill directory, relative to that directory. */
export interface GitcodeFileEntry {
  path: string
  size?: number | null
  contentType?: string | null
}

export interface GitcodeSkillSummary {
  path: string
  name: string
  version?: string | null
  description?: string | null
  fileCount?: number | null
  totalBytes?: number | null
}

export interface GitcodeInspectResult {
  owner: string
  repository: string
  defaultBranch?: string | null
  ref: string
  refType?: GitcodeRefType | null
  commitSha?: string | null
  skills: GitcodeSkillSummary[]
  /** Present when the request pinned one skill directory through `path`. */
  files?: GitcodeFileEntry[] | null
  skillMdPath?: string | null
  skillMd?: string | null
}

export interface GitcodeImportRequest {
  repoUrl: string
  ref?: string
  path: string
  visibility: string
  confirmWarnings?: boolean
  displayName?: string
  changelog?: string
  /**
   * Only needed when neither a tag ref nor the SKILL.md frontmatter names a version: real skill
   * repositories usually ship no tag and no `version:` line, so the wizard collects one and the
   * backend writes it into the imported frontmatter.
   */
  version?: string
}

/** The import reuses the existing publish pipeline, so the response is the publish response. */
export type GitcodeImportResult = PublishResult
