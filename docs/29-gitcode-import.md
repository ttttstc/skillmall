# GitCode import: pinned-snapshot publishing

SkillHub can publish a skill directly from a public GitCode repository by pinning one commit.
A preview and its publication always observe the same commit: the repository's default branch is
resolved once, and the publication re-reads that commit instead of the branch.

Authoritative sources remain [skill lifecycle](14-skill-lifecycle.md) (version/publish semantics),
[skill package protocol](07-skill-protocol.md) (package rules) and the code under
`server/skillhub-app/src/main/java/com/iflytek/skillhub/gitcode/`. This page only records what the
import path itself does and does not do.

## Scope

In scope: importing a skill from a public GitCode repository, choosing which skill directory to
import when the repository holds several, previewing the exact file set, and publishing it as one
version with provenance.

Out of scope (by design, not by omission): GitCode account login, private repositories, scheduled or
automatic re-synchronisation of an imported version, review workflows and the GitHub sync
subsystem. Imported versions are never updated in the background; a user imports a new snapshot
explicitly to publish an update.

## Endpoints

All three require an authenticated SkillHub user (`401` for anonymous callers, never `403` or a
success). They are registered in `RouteSecurityPolicyRegistry`; API tokens need the scopes shown.

| Method | Path | Token scope |
| --- | --- | --- |
| `POST` | `/api/web/skills/import/gitcode/resolve` | `skill:publish` |
| `POST` | `/api/web/skills/import/gitcode/publish` | `skill:publish` |
| `GET` | `/api/web/skills/{namespace}/{slug}/versions/{version}/import-source` | `skill:read` |

`resolve` is read-only: without `path` it returns the pinned commit, the default branch name (when
it can be determined) and every directory that contains a `SKILL.md`; with `path` it additionally
returns the file list, file count, total size and a content digest.

`publish` requires the commit and the digest returned by the preview. It re-reads that commit, checks
the digest, and writes the version, its provenance row and the audit entry in one transaction.

## Accepted repository URLs

Only `https://gitcode.com/<owner>/<repository>` is accepted, including one level of nested GitCode
group (`https://gitcode.com/<group>/<subgroup>/<repository>`), which is how mirrors and
organisational groups are addressed. Everything else fails with a named error instead of an opaque
one:

- other hosts and other schemes (including `http`, `git://`, `ssh://`, `git@host:path`) →
  `error.skill.import.source.unsupported`
- credentials in the URL, a port, a query or a fragment → `error.skill.import.url.invalid`
- a browser view such as `/tree/<branch>` or `/blob/<ref>/<file>`, or deeper paths →
  `error.skill.import.url.invalid`

The persisted `repo_url` is the canonical, credential-free form.

## Pinned snapshot

- `resolve` reads `<repo>/commits` and pins the returned head SHA. Branch names are advisory.
- Every content request is addressed by commit SHA; the importer never falls back to a branch name.
- `publish` never resolves a branch. If the default branch moved after the preview, publishing the
  previewed commit still succeeds and still publishes the previewed content.
- If the pinned commit can no longer be read, the import fails with
  `error.skill.import.snapshot.unavailable` (not a 500) and nothing is written.
- If the content at that commit no longer matches the previewed digest, the import fails with
  `error.skill.import.snapshot.changed` and nothing is written.

GitCode rate limits anonymous requests. A `429` is retried a bounded number of times and then
reported as `error.skill.import.source.busy`; it is never reported as an unreachable repository.

## Path and file safety

The selected path must be a repository-relative directory: absolute paths, `..`, `.`, empty
segments, drive letters, `~` and control characters are rejected with
`error.skill.import.path.unsafe`. Paths reported by the provider are re-validated the same way
before they become package entries.

The file set below the selected path is rejected when it contains:

- a submodule or a symlink (`error.skill.import.file.invalid`) — a symlink could point outside the
  skill root, and a submodule has no content in this repository
- a file that contradicts its extension, or a non-UTF-8/NUL-containing file behind a text
  extension (`error.skill.import.file.invalid`)
- an extensionless file that is not valid UTF-8 text (`error.skill.import.file.binary`)
- a file type outside the publish allowlist (`error.skill.import.file.unsupported`)
- more files, a larger single file, or a larger package than the publish limits allow
  (`error.skill.import.limit.exceeded`). Limits are the shared `skillhub.publish` limits, plus
  bounded repository walking (`skillhub.gitcode-import.max-directories`, `max-depth`,
  `max-path-length`).

A directory without `SKILL.md` fails with `error.skill.import.skill.notFound`. All of these
failures happen before any write, so a rejected import leaves no skill, version or provenance row
behind.

## Provenance

`V121__skill_import_source.sql` adds `skill_import_source`, one row per imported version:

| Column | Meaning |
| --- | --- |
| `provider` | `GITCODE` |
| `repo_url`, `repo_owner`, `repo_name` | canonical repository identity |
| `branch` | branch name observed at resolve time (descriptive only) |
| `commit_sha` | the full 40-character commit the version was published from |
| `skill_path` | repository-relative directory of the skill |
| `content_digest` | SHA-256 rollup over the published `{path, sha256}` list |
| `file_manifest`, `file_count`, `total_size` | exact published file set |
| `imported_by`, `created_at` | who imported it and when |

`GET /api/web/skills/{namespace}/{slug}/versions/{version}/import-source` reads this row back. The
row is addressed by the same coordinates as the version detail endpoint and is only returned after
that endpoint's visibility-checked lookup succeeds, so provenance (including who imported the
version) is no easier to read than the version itself. The digest and manifest are enough to compare
a published version against the repository later without re-downloading it.

## Testing

- `GitCodeRepoUrlTest`, `GitCodeHttpApiClientTest`, `GitCodeSnapshotMaterializerTest` and
  `GitCodeImportAppServiceTest` cover URL acceptance, provider error mapping, multi-skill
  discovery, path/file rejection, and the "publish the previewed commit after the branch moved"
  invariant.
- `GitCodeImportSecurityIntegrationTest` boots the production security chain and asserts `401` for
  anonymous callers, token scope enforcement, and the provenance endpoint.
- The end-to-end check against a real public GitCode repository is recorded in the pull request
  that introduced this feature.
