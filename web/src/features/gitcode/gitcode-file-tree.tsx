import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronDown, ChevronRight, File, FileCode, FileText, Folder, FolderOpen } from 'lucide-react'
import { getFileExtension } from '@/features/skill/file-type-utils'
import { cn } from '@/shared/lib/utils'
import type { GitcodeFileEntry } from './types'

interface RepoTreeNode {
  name: string
  path: string
  type: 'file' | 'directory'
  size: number
  children: RepoTreeNode[]
}

interface GitcodeFileTreeProps {
  files: readonly GitcodeFileEntry[]
  /** File currently rendered in the preview pane. */
  selectedPath: string | null
  /** Directory levels that start expanded, normally the ones leading to SKILL.md. */
  initiallyExpanded: ReadonlySet<string>
  onSelectFile: (path: string) => void
}

const CODE_EXTENSIONS = new Set(['ts', 'tsx', 'js', 'jsx', 'mjs', 'cjs', 'py', 'java', 'go', 'rs', 'sh', 'bash', 'json', 'yaml', 'yml', 'toml'])

function buildRepoTree(files: readonly GitcodeFileEntry[]): RepoTreeNode[] {
  const roots: RepoTreeNode[] = []
  const directories = new Map<string, RepoTreeNode>()

  for (const file of [...files].sort((a, b) => a.path.localeCompare(b.path))) {
    const parts = file.path.split('/').filter(Boolean)
    if (parts.length === 0) continue

    let level = roots
    let currentPath = ''
    parts.forEach((part, index) => {
      currentPath = currentPath ? `${currentPath}/${part}` : part
      if (index === parts.length - 1) {
        level.push({ name: part, path: currentPath, type: 'file', size: file.size ?? 0, children: [] })
        return
      }

      let directory = directories.get(currentPath)
      if (!directory) {
        directory = { name: part, path: currentPath, type: 'directory', size: 0, children: [] }
        directories.set(currentPath, directory)
        level.push(directory)
      }
      level = directory.children
    })
  }

  const sortLevel = (nodes: RepoTreeNode[]) => {
    nodes.sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'directory' ? -1 : 1))
    nodes.forEach((node) => sortLevel(node.children))
  }
  sortLevel(roots)
  return roots
}

/** Directory paths that must be open for `path` to be visible. */
export function ancestorDirectories(path?: string | null): Set<string> {
  const ancestors = new Set<string>()
  if (!path) return ancestors

  const parts = path.split('/').filter(Boolean)
  for (let index = 1; index < parts.length; index += 1) {
    ancestors.add(parts.slice(0, index).join('/'))
  }
  return ancestors
}

function formatFileSize(bytes: number): string {
  if (bytes <= 0) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function FileIcon({ name }: { name: string }) {
  const extension = getFileExtension(name)
  if (['md', 'mdx'].includes(extension)) return <FileText aria-hidden="true" className="h-4 w-4 shrink-0 text-muted-foreground" />
  if (CODE_EXTENSIONS.has(extension)) return <FileCode aria-hidden="true" className="h-4 w-4 shrink-0 text-muted-foreground" />
  return <File aria-hidden="true" className="h-4 w-4 shrink-0 text-muted-foreground" />
}

function TreeNodeRow({ node, depth, selectedPath, initiallyExpanded, onSelectFile }: {
  node: RepoTreeNode
  depth: number
  selectedPath: string | null
  initiallyExpanded: ReadonlySet<string>
  onSelectFile: (path: string) => void
}) {
  const [expanded, setExpanded] = useState(() => initiallyExpanded.has(node.path))

  if (node.type === 'directory') {
    return (
      <div>
        <button
          type="button"
          data-analytics-id="skill.import.directory.toggle"
          aria-expanded={expanded}
          onClick={() => setExpanded((value) => !value)}
          className="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left transition-colors hover:bg-muted/60"
          style={{ paddingLeft: `${depth * 14 + 8}px` }}
        >
          {expanded
            ? <ChevronDown aria-hidden="true" className="h-4 w-4 shrink-0 text-muted-foreground" />
            : <ChevronRight aria-hidden="true" className="h-4 w-4 shrink-0 text-muted-foreground" />}
          {expanded
            ? <FolderOpen aria-hidden="true" className="h-4 w-4 shrink-0 text-amber-500" />
            : <Folder aria-hidden="true" className="h-4 w-4 shrink-0 text-amber-500" />}
          <span className="truncate font-mono text-sm text-foreground">{node.name}</span>
        </button>
        {expanded && node.children.map((child) => (
          <TreeNodeRow
            key={child.path}
            node={child}
            depth={depth + 1}
            selectedPath={selectedPath}
            initiallyExpanded={initiallyExpanded}
            onSelectFile={onSelectFile}
          />
        ))}
      </div>
    )
  }

  const selected = node.path === selectedPath
  const size = formatFileSize(node.size)

  return (
    <button
      type="button"
      data-analytics-id={selected ? undefined : 'skill.import.file.open'}
      aria-current={selected ? 'true' : undefined}
      onClick={() => onSelectFile(node.path)}
      className={cn(
        'flex w-full items-center gap-2 rounded px-2 py-1.5 text-left transition-colors hover:bg-muted/60',
        selected && 'bg-secondary/70'
      )}
      style={{ paddingLeft: `${depth * 14 + 8}px` }}
    >
      <FileIcon name={node.name} />
      <span className="min-w-0 flex-1 truncate font-mono text-sm text-foreground">{node.name}</span>
      {size ? <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{size}</span> : null}
    </button>
  )
}

/**
 * Collapsible repository tree for step 2.
 *
 * A local tree instead of `features/skill/file-tree` because this step needs two behaviours that
 * component does not have: opening the levels that lead to SKILL.md on mount and highlighting the
 * file that is currently rendered in the preview pane.
 */
export function GitcodeFileTree({ files, selectedPath, initiallyExpanded, onSelectFile }: GitcodeFileTreeProps) {
  const { t } = useTranslation()
  const nodes = buildRepoTree(files)

  if (nodes.length === 0) {
    return <p className="px-2 py-3 text-xs text-muted-foreground">{t('skillDetail.noFiles')}</p>
  }

  return (
    <div className="space-y-0.5">
      {nodes.map((node) => (
        <TreeNodeRow
          key={node.path}
          node={node}
          depth={0}
          selectedPath={selectedPath}
          initiallyExpanded={initiallyExpanded}
          onSelectFile={onSelectFile}
        />
      ))}
    </div>
  )
}
