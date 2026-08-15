// The class strings behind the UI primitives. They live outside components/ui.tsx because a
// module that exports both components and plain values loses React Fast Refresh — editing a
// button would full-reload the page instead of hot-swapping it.
//
// Colours here are always tokens (see index.css) — no literal hex, no `dark:` variants.

export function cx(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ')
}

/** A raised block of content sitting on the page ground. */
export const PANEL = 'rounded-card border border-line bg-surface p-5 shadow-card'

/** Inputs, textareas and selects all share one skin. */
export const CONTROL =
  'w-full rounded-ctl border border-line bg-surface-2 px-3 py-2 text-sm text-ink outline-none ' +
  'transition duration-150 focus:border-accent disabled:opacity-50'

export type ButtonVariant = 'primary' | 'ghost' | 'quiet'
export type ButtonSize = 'sm' | 'md'

export const BUTTON_BASE =
  'inline-flex cursor-pointer items-center justify-center gap-2 rounded-ctl font-medium transition ' +
  'duration-150 disabled:cursor-not-allowed disabled:opacity-45'

export const BUTTON_VARIANT: Record<ButtonVariant, string> = {
  // The accent is spent here and almost nowhere else — one saturated cyan per screen.
  // accent-deep is the dim rim that gives the fill an edge against the ground.
  primary:
    'bg-accent text-on-accent border border-accent-deep hover:brightness-105 active:translate-y-px',
  ghost: 'border border-line text-ink hover:bg-surface-2 active:translate-y-px',
  quiet: 'border-0 bg-transparent text-ink-muted hover:text-ink underline-offset-4 hover:underline',
}

export function buttonClass(variant: ButtonVariant = 'ghost', size: ButtonSize = 'md'): string {
  const pad =
    variant === 'quiet'
      ? 'text-[13px]'
      : size === 'sm'
        ? 'px-3 py-1.5 text-[13px]'
        : 'px-4 py-2 text-sm'
  return cx(BUTTON_BASE, BUTTON_VARIANT[variant], pad)
}

/** Same skin as <Button>, for react-router <Link>s that read as actions. */
export const linkButton = buttonClass

export type Tone = 'neutral' | 'ok' | 'warn' | 'bad' | 'accent'

export const TONE: Record<Tone, string> = {
  neutral: 'border-line bg-surface-2 text-ink-muted',
  ok: 'border-ok/40 bg-ok-bg text-ok',
  warn: 'border-warn/40 bg-warn-bg text-warn',
  bad: 'border-bad/40 bg-bad-bg text-bad',
  accent: 'border-accent-deep bg-accent text-on-accent',
}
