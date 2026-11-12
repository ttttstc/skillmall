import type { ReactNode } from 'react'

function LaurelBranch({ className }: { className?: string }) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 44"
      className={className}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.3"
      strokeLinecap="round"
    >
      <path d="M19 3c-7 4-11 10-11 18s4 15 11 20" />
      <path d="M14 11c-3 0-6 2-8 5" />
      <path d="M11 19c-3 0-6 2-8 5" />
      <path d="M10 27c-3 0-6 2-8 5" />
      <path d="M12 35c-3 0-6 2-8 5" />
    </svg>
  )
}

interface DecoratedSectionTitleProps {
  title: string
  subtitle?: ReactNode
}

/**
 * Section heading of the home design: centred 32px bold title flanked by 16×28 laurel sprigs
 * with a 17px gap, plus an optional 18px subtitle 18px below the title.
 *
 * The sprigs are pure decoration, so they stay out of the accessibility tree.
 */
export function DecoratedSectionTitle({ title, subtitle }: DecoratedSectionTitleProps) {
  return (
    <div className="text-center">
      <div className="flex items-center justify-center gap-[17px]">
        <LaurelBranch className="h-7 w-4 shrink-0 text-muted-foreground/50" />
        <h2 className="text-[32px] font-bold leading-none tracking-tight text-foreground">{title}</h2>
        <LaurelBranch className="h-7 w-4 shrink-0 -scale-x-100 text-muted-foreground/50" />
      </div>
      {subtitle ? <p className="mt-[18px] text-[18px] text-muted-foreground">{subtitle}</p> : null}
    </div>
  )
}
