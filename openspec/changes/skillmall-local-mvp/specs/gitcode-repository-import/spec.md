## ADDED Requirements

### Requirement: Import a skill from a public GitCode repository

The system SHALL let an authenticated user publish a new skill version whose package comes from a
public GitCode repository instead of a local archive upload.

Delivered by: backend `GitcodeUrlParser` / `GitcodeClient` / `GitcodeSkillScanner` /
`GitcodeImportAppService` / `GitcodeImportController`; migration
`V121__gitcode_source_binding.sql`; frontend `web/src/features/gitcode/**` and the publish page
source switch.

#### Scenario: Repository root holds the skill

- **WHEN** the user submits `https://gitcode.com/{owner}/{repo}` and `SKILL.md` sits at the
  repository root
- **THEN** the system discovers exactly one skill with path `""`, resolves the default branch,
  pins the resolved commit, and can preview and import it

#### Scenario: Skill lives in a subdirectory

- **WHEN** the address carries a tree path (`https://gitcode.com/{owner}/{repo}/tree/{ref}/{path}`)
  or the user selects one of several discovered directories
- **THEN** only files under that directory become package entries, and the skill path is recorded
  with the binding

#### Scenario: Several skills in one repository

- **WHEN** discovery finds more than one directory containing `SKILL.md` and the request does not
  pin a path
- **THEN** the system returns every candidate with its name, version, description and file count,
  imports nothing, and reports `error.gitcode.skillAmbiguous` only if an import is attempted
  without choosing one

#### Scenario: No skill in the address

- **WHEN** no `SKILL.md` exists in the requested subtree
- **THEN** the system fails with `error.gitcode.skillMd.notFound` and writes no binding

#### Scenario: Address is not a GitCode repository

- **WHEN** the address is not an `https` `gitcode.com` repository URL (other host, other scheme,
  malformed owner/repository, parent-directory segments)
- **THEN** the system fails with `error.gitcode.url.invalid` before any outbound request

#### Scenario: Repository is missing or private

- **WHEN** GitCode answers 401/403/404 for the repository
- **THEN** the system fails with `error.gitcode.repo.notFound` and does not disclose whether the
  repository exists

### Requirement: Preview the exact package before publishing

The system SHALL show the skill metadata, the file list and the `SKILL.md` body of the skill that
is about to be imported, using the same revision that the import will use.

Delivered by: `POST /api/v1|/api/web/skills/gitcode/inspect` with the optional `path`; frontend
wizard steps ① address and ② file preview.

#### Scenario: Address step discovers skills cheaply

- **WHEN** the user submits an address without pinning a path
- **THEN** the system reads the repository tree and one `SKILL.md` per candidate skill directory
  only, and returns candidates without downloading the other files

#### Scenario: Preview step loads one package

- **WHEN** the request pins a discovered `path`
- **THEN** the response additionally carries that directory's file list (`path`, `size`), the
  `SKILL.md` path and its full text, and the import uses the same commit sha

#### Scenario: Revision is pinned for both steps

- **WHEN** the preview resolved a branch or tag to a commit
- **THEN** both the preview and the import address that commit sha, so content cannot change
  between preview and publish

### Requirement: Reuse the existing publish pipeline

The system SHALL publish a repository import through the same validation, scanning, versioning,
visibility and audit path as an archive upload, without a second publish implementation.

Delivered by: `GitcodeImportAppService` building `PackageEntry` values and calling
`SkillPublishAppService.publish(...)`.

#### Scenario: Package validation and scanning still apply

- **WHEN** the imported package violates package policy or triggers the security scanner
- **THEN** the outcome is identical to the same files uploaded as a ZIP, and scan results are
  recorded the same way

#### Scenario: Version resolution never invents a version

- **WHEN** the ref is a tag
- **THEN** that tag name becomes the published version
- **WHEN** the ref is not a tag and `SKILL.md` frontmatter carries `version`
- **THEN** that value becomes the published version
- **WHEN** neither exists
- **THEN** the import is refused with the existing required-field error and nothing is published

#### Scenario: Visibility follows the local-upload policy

- **WHEN** the user imports with `PUBLIC` or `NAMESPACE_ONLY`
- **THEN** the version is published directly after package validation, exactly like a local
  upload in the current release
- **WHEN** the user imports with `PRIVATE`
- **THEN** the version lands in the uploaded/private state, exactly like a local upload

#### Scenario: Version conflict keeps existing semantics

- **WHEN** the resolved version already exists for that skill
- **THEN** the existing publish conflict behaviour applies; the import does not overwrite

### Requirement: Record the repository source binding

The system SHALL persist one `gitcode_source_binding` row per namespace, repository and skill
path, independent of `github_source_binding`, and only after a successful publication.

Delivered by: `GitcodeSourceBinding`, `GitcodeSourceBindingRepository`,
`GitcodeSourceBindingAppService`, `GET /api/v1|/api/web/namespaces/{namespace}/gitcode-sources`.

#### Scenario: Successful import records the source

- **WHEN** an import publishes a version
- **THEN** the binding stores owner, repository, skill path, ref, ref type, commit sha, link type
  (`ONE_SHOT`), visibility, creator, last import time, skill id and version id

#### Scenario: Failed import leaves no binding

- **WHEN** publication fails for any reason
- **THEN** no binding row is written (binding and publication share one transaction)

#### Scenario: Re-import of the same commit is idempotent

- **WHEN** the same namespace, repository, path, commit and visibility are imported again
- **THEN** the existing published version is returned, `lastImportedAt` is refreshed, and no
  second publication happens

#### Scenario: Different subdirectory is a different source

- **WHEN** two directories of one repository are imported into the same namespace
- **THEN** two binding rows exist, distinguished by `skill_path`, and the uniqueness constraint
  covers `(namespace_id, owner_login, repository_name, skill_path)`

#### Scenario: Different commit is a new attempt

- **WHEN** the repository moved to another commit since the last import
- **THEN** the import goes through the normal publish path, subject to existing version rules

### Requirement: Bound the outbound work and protect the server

The system SHALL reach only GitCode hosts, refuse private-network targets, and bound every
import by time, size and count budgets so a hostile or huge repository cannot exhaust the server.

Delivered by: `GitcodeClient` (host allow-list, per-request timeout, total budget, bounded body
reads), `GitcodeSkillScanner` limits, `@RateLimit(category = "gitcode")`.

#### Scenario: Only GitCode hosts are contacted

- **WHEN** a request would call a host outside `api.gitcode.com`, `gitcode.com`,
  `raw.gitcode.com`
- **THEN** the call is refused (`error.gitcode.url.invalid`) and nothing is fetched

#### Scenario: Repository exceeds import bounds

- **WHEN** the chosen skill directory exceeds the file count, per-file size or total size limit
- **THEN** the import fails with `error.gitcode.tooLarge` or `error.gitcode.tooManyFiles`
- **WHEN** the repository tree exceeds the discovery entry cap
- **THEN** discovery fails with `error.gitcode.tooManyFiles`

#### Scenario: GitCode rate-limits the anonymous read

- **WHEN** GitCode answers HTTP 429
- **THEN** the system fails with `error.gitcode.rateLimited` (not "unreachable"), so the wizard can
  ask the user to retry instead of reporting a dead host

#### Scenario: Remote is unreachable

- **WHEN** GitCode cannot be reached or the total budget expires
- **THEN** the system fails with `error.gitcode.unreachable`

### Requirement: Keep the existing local upload flow untouched

The system SHALL keep the archive-upload publish path behaviourally identical while adding the
repository source.

Delivered by: publish page source switch; `features/publish/source-switch.tsx`; preserved
`data-analytics-id` markers of the local flow.

#### Scenario: Switching source does not disturb local state

- **WHEN** the user switches between `本地上传` and `代码仓导入`
- **THEN** the local upload selection, package preview, avatar and form values are preserved, and
  neither flow issues requests for the other

#### Scenario: Local flow contract unchanged

- **WHEN** the user completes a local upload publish
- **THEN** requests, validation, visibility handling, analytics markers and outcomes are the same
  as before this change

#### Scenario: Update and resubmit are local-only

- **WHEN** the page is opened for an existing skill update or a rejected-skill resubmission
- **THEN** only the local upload source is offered

## Non-goals (deferred)

- Private GitCode repositories (PAT/OAuth credentials) — public repositories only in this change.
- Continuous synchronisation (`BOUND` link type, webhooks, polling, sync review queue).
- GitCode-side repository creation, branch protection or status reporting.
- Repository-wide bulk import of every skill in one action.
- Detail-page "来自 GitCode" badge: the binding already stores repository/path/commit, but the
  skill detail projection and UI are a follow-up change.

## Verification

Automated (backend): `GitcodeUrlParserTest`, `GitcodeSkillScannerTest`,
`GitcodeImportAppServiceTest`, `GitcodeSourceBindingAppServiceTest`,
`GitcodeSourceBindingPostgresTest` run with
`./mvnw.cmd -pl skillhub-app -am -Dtest='Gitcode*Test' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test`.

Automated (frontend): `pnpm run typecheck`, `pnpm run lint`, `analytics-manifest` test after
regenerating `docs/frontend-analytics-manifest.json`.

Manual (must be recorded with a real repository): inspect a public repository containing
`SKILL.md`, confirm the candidate list, pin a path, confirm the preview payload (file list +
`SKILL.md` text), import into a namespace, and confirm the published skill version, the
`gitcode_source_binding` row, and that no unselected directory was published.
