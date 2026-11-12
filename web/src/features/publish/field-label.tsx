import type { ReactNode } from 'react'
import { Label } from '@/shared/ui/label'

/**
 * UCD field label: 12px semibold, required mark before the text.
 *
 * Shared by the local upload form and the repository import wizard so both present the same
 * field labels.
 */
export function FieldLabel({ htmlFor, required, children }: { htmlFor?: string; required?: boolean; children: ReactNode }) {
  return (
    <Label htmlFor={htmlFor} className="flex items-center gap-1 text-xs font-semibold text-foreground">
      {required ? <span aria-hidden="true" className="text-destructive">*</span> : null}
      {children}
    </Label>
  )
}
