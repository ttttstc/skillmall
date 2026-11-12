import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Activity, Shield, ShieldAlert, ShieldCheck, ShieldX } from 'lucide-react'
import { cn } from '@/shared/lib/utils'
import { useSecurityAudits } from './use-security-audit'
import { getSecurityAuditDisplayState } from './display-state'
import type { SecurityAuditDisplayState, SecurityAuditRecord } from './types'
import { SecurityAuditDialog } from './security-audit-dialog'

const VERDICT_RANK: Record<string, number> = { SAFE: 0, SUSPICIOUS: 1, DANGEROUS: 2, BLOCKED: 3 }

/**
 * Collapses per-scanner records into one headline state.
 *
 * A failed or unfinished scan wins over any conclusion: it means no verdict has been established
 * for this version yet, so the cell must not imply one.
 */
function resolveHeadlineState(
  audits: SecurityAuditRecord[],
  versionStatus?: string,
): SecurityAuditDisplayState | null {
  if (audits.length === 0) {
    return null
  }

  const states = audits.map((audit) => getSecurityAuditDisplayState(audit, versionStatus))

  if (states.includes('SCAN_FAILED')) {
    return 'SCAN_FAILED'
  }
  if (states.includes('SCANNING')) {
    return 'SCANNING'
  }

  const verdicts = states.filter((state) => state in VERDICT_RANK)
  return verdicts.sort((left, right) => (VERDICT_RANK[right] ?? 0) - (VERDICT_RANK[left] ?? 0))[0] ?? null
}

interface Tone {
  wrapper: string
  label: string
  icon: string
  Icon: typeof Shield
}

const TONES: Record<'SAFE' | 'SUSPICIOUS' | 'DANGEROUS' | 'SCANNING' | 'SCAN_FAILED' | 'NOT_EVALUATED', Tone> = {
  SAFE: {
    wrapper: 'bg-emerald-500/5',
    label: 'text-emerald-700 dark:text-emerald-400',
    icon: 'text-emerald-600 dark:text-emerald-400',
    Icon: ShieldCheck,
  },
  SUSPICIOUS: {
    wrapper: 'bg-amber-500/5',
    label: 'text-amber-700 dark:text-amber-400',
    icon: 'text-amber-600 dark:text-amber-400',
    Icon: ShieldAlert,
  },
  DANGEROUS: {
    wrapper: 'bg-red-500/5',
    label: 'text-red-700 dark:text-red-400',
    icon: 'text-red-600 dark:text-red-400',
    Icon: ShieldAlert,
  },
  SCANNING: {
    wrapper: 'bg-sky-500/5',
    label: 'text-sky-700 dark:text-sky-400',
    icon: 'text-sky-600 dark:text-sky-400',
    Icon: Shield,
  },
  SCAN_FAILED: {
    wrapper: 'bg-red-500/5',
    label: 'text-red-700 dark:text-red-400',
    icon: 'text-red-600 dark:text-red-400',
    Icon: ShieldX,
  },
  NOT_EVALUATED: {
    wrapper: 'bg-muted/30',
    label: 'text-muted-foreground',
    icon: 'text-muted-foreground',
    Icon: Shield,
  },
}

function resolveToneKey(state: SecurityAuditDisplayState | null): keyof typeof TONES {
  if (state === null) {
    return 'NOT_EVALUATED'
  }
  if (state === 'DANGEROUS' || state === 'BLOCKED') {
    return 'DANGEROUS'
  }
  if (state === 'SAFE' || state === 'SUSPICIOUS' || state === 'SCANNING' || state === 'SCAN_FAILED') {
    return state
  }
  return 'NOT_EVALUATED'
}

interface SecurityStatusCellProps {
  skillId: number
  versionId?: number
  versionStatus?: string
  /** Audit evidence is read only where the viewer is allowed to see it. */
  canViewAudit: boolean
  className?: string
}

/**
 * Security slot of the detail header statistics bar.
 *
 * It reports the scan state the platform actually holds for this version. When there is no
 * evaluation it says so ("未评测") instead of rendering a passing shield, because the market first
 * release does not run automated security evaluation.
 */
export function SecurityStatusCell({ skillId, versionId, versionStatus, canViewAudit, className }: SecurityStatusCellProps) {
  const { t } = useTranslation()
  const [dialogOpen, setDialogOpen] = useState(false)
  const { data: audits } = useSecurityAudits(
    canViewAudit ? skillId : undefined,
    canViewAudit ? versionId : undefined,
  )

  const records = audits ?? []
  const state = resolveHeadlineState(records, versionStatus)
  const tone = TONES[resolveToneKey(state)]

  const label = state === null || state === 'SCANNING' || state === 'SCAN_FAILED'
    ? state === 'SCANNING'
      ? t('securityAudit.statusScanning')
      : state === 'SCAN_FAILED'
        ? t('securityAudit.statusScanFailed')
        : t('securityAudit.notEvaluatedTitle')
    : state === 'SAFE'
      ? t('securityAudit.statusSafe', { defaultValue: '安全' })
      : t(`securityAudit.verdict.${state}`)

  return (
    <div
      className={cn(
        'relative flex min-h-[92px] flex-col items-center justify-center gap-1.5 overflow-hidden px-3 py-4 text-center sm:border-l sm:border-border/60',
        tone.wrapper,
        className,
      )}
      title={state === null ? t('securityAudit.notEvaluatedDescription') : undefined}
    >
      <Activity
        className="pointer-events-none absolute -right-1 top-1/2 h-20 w-20 -translate-y-1/2 opacity-[0.07]"
        aria-hidden="true"
      />
      <span className={cn('relative flex items-center gap-1.5 text-sm font-semibold', tone.label)}>
        <tone.Icon className={cn('h-4 w-4', tone.icon)} aria-hidden="true" />
        {label}
      </span>
      {canViewAudit && versionId !== undefined && records.length > 0 ? (
        <button
          type="button"
          onClick={() => setDialogOpen(true)}
          className="relative rounded-sm text-xs font-medium text-foreground/80 underline-offset-2 transition-colors hover:text-foreground hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/70 focus-visible:ring-offset-2"
        >
          {t('securityAudit.viewRiskReport', { defaultValue: '查看风险报告' })}
        </button>
      ) : (
        <span className="relative text-xs leading-4 text-muted-foreground">
          {t('securityAudit.notEvaluatedHint', { defaultValue: '本期不提供自动安全评测' })}
        </span>
      )}

      {canViewAudit && versionId !== undefined && (
        <SecurityAuditDialog
          open={dialogOpen}
          onOpenChange={setDialogOpen}
          skillId={skillId}
          versionId={versionId}
          versionStatus={versionStatus}
        />
      )}
    </div>
  )
}
