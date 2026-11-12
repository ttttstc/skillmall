import type { ReactNode } from 'react'

/**
 * Laurel branch drawn as a curved stem with leaves, used to frame the discovery section headings
 * the way the UCD home page does.
 */
function LaurelBranch({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 34" aria-hidden="true" className={className} fill="currentColor">
      <path
        d="M28.6 32c-8.4-2.6-15-8.6-18.4-16.6"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.3"
        strokeLinecap="round"
      />
      <ellipse cx="8.6" cy="13.4" rx="4.6" ry="1.9" transform="rotate(-38 8.6 13.4)" opacity="0.9" />
      <ellipse cx="12.4" cy="18.8" rx="4.9" ry="2" transform="rotate(-30 12.4 18.8)" opacity="0.9" />
      <ellipse cx="17.4" cy="23.4" rx="4.9" ry="2" transform="rotate(-18 17.4 23.4)" opacity="0.9" />
      <ellipse cx="23.2" cy="27" rx="4.6" ry="1.9" transform="rotate(-8 23.2 27)" opacity="0.9" />
      <ellipse cx="12.8" cy="10.6" rx="3.4" ry="1.5" transform="rotate(24 12.8 10.6)" opacity="0.5" />
      <ellipse cx="17.8" cy="15.4" rx="3.4" ry="1.5" transform="rotate(32 17.8 15.4)" opacity="0.5" />
      <ellipse cx="23.4" cy="20.6" rx="3.2" ry="1.4" transform="rotate(40 23.4 20.6)" opacity="0.5" />
    </svg>
  )
}

interface SectionHeadingProps {
  children: ReactNode
  className?: string
}

/** Centered discovery heading with the UCD laurel decorations and title colour. */
export function SectionHeading({ children, className = '' }: SectionHeadingProps) {
  return (
    <div className={`flex items-center justify-center gap-3 ${className}`}>
      <LaurelBranch className="h-8 w-8 -scale-x-100 text-[#808080]" />
      <h2 className="text-3xl font-semibold tracking-tight text-[#182333]">{children}</h2>
      <LaurelBranch className="h-8 w-8 text-[#808080]" />
    </div>
  )
}
