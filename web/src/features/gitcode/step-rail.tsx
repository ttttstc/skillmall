import { Check } from 'lucide-react'
import { cn } from '@/shared/lib/utils'

export interface StepRailItem {
  /** Stable key for React and for the current-step assertion in tests. */
  key: string
  label: string
}

interface StepRailProps {
  steps: readonly StepRailItem[]
  /** Zero-based index of the step being edited. */
  current: number
  className?: string
}

/**
 * Vertical wizard rail from the UCD: a filled dot marks the current step, a check the completed
 * ones, the rest stay outlined, and a hairline joins the badges.
 */
export function StepRail({ steps, current, className }: StepRailProps) {
  return (
    <ol className={cn('space-y-0', className)}>
      {steps.map((step, index) => {
        const state = index < current ? 'done' : index === current ? 'current' : 'todo'
        const isLast = index === steps.length - 1
        return (
          <li
            key={step.key}
            aria-current={state === 'current' ? 'step' : undefined}
            className="relative flex items-start gap-3 pb-8 last:pb-0"
          >
            {!isLast && (
              <span aria-hidden="true" className="absolute left-[11px] top-6 h-[calc(100%-1rem)] w-px bg-border" />
            )}
            <span
              className={cn(
                'relative z-10 flex h-6 w-6 shrink-0 items-center justify-center rounded-full border text-xs font-medium',
                state === 'current' && 'border-foreground bg-foreground text-background',
                state === 'done' && 'border-border bg-background text-foreground',
                state === 'todo' && 'border-border bg-background text-muted-foreground'
              )}
            >
              {state === 'done' ? <Check aria-hidden="true" className="h-3.5 w-3.5" /> : <span aria-hidden="true">{index + 1}</span>}
            </span>
            <span className={cn('pt-0.5 text-sm', state === 'current' ? 'font-medium text-foreground' : 'text-muted-foreground')}>
              {step.label}
            </span>
          </li>
        )
      })}
    </ol>
  )
}
