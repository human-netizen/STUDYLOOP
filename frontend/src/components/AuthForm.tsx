import type { ReactNode } from 'react'
import { Eyebrow, Field, Input } from './ui'
import { cx } from '../lib/style'

// Sign-in and sign-up share this frame: a raised slab on the left carrying the wordmark, the
// form on the page ground to its right. Below 1024px the slab collapses into a header strip
// so the form still opens at the top of a phone screen.
//
// The slab is the one place the ambient warm key light appears — a low-alpha radial behind
// the wordmark (.warm-key), which is why the type reads as lit rather than as flat fill.

export function AuthShell({
  title,
  intro,
  children,
  footer,
}: {
  title: string
  intro: string
  children: ReactNode
  footer: ReactNode
}) {
  return (
    <div className="grid min-h-screen lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
      <aside className="warm-key flex flex-col justify-between gap-8 border-b border-line bg-surface px-6 py-10 sm:px-10 lg:border-r lg:border-b-0 lg:px-14 lg:py-14">
        <div>
          <Eyebrow>StudyLoop</Eyebrow>
          <p className="mt-6 mb-0 font-display text-[clamp(40px,6vw,72px)] leading-[0.92] font-bold tracking-[-0.04em] text-ink">
            Your
            <br />
            materials,
            <br />
            <span className="text-accent">answering back.</span>
          </p>
        </div>
        <p className="m-0 max-w-[34ch] text-sm text-ink-2">
          Upload a course's PDFs, then ask them questions, generate quizzes from them, and
          drill the answers as flashcards — every answer cites the page it came from.
        </p>
      </aside>

      <main className="flex items-center px-6 py-12 sm:px-10 lg:px-14">
        <div className="w-full max-w-[27rem]">
          <h1 className="m-0 text-[32px] leading-[1.05] tracking-[-0.03em]">{title}</h1>
          <p className="mt-2 mb-8 text-sm text-ink-muted">{intro}</p>
          {children}
          <div className="mt-8 border-t border-line pt-5 text-sm text-ink-muted">{footer}</div>
        </div>
      </main>
    </div>
  )
}

export function AuthField({
  label,
  type,
  value,
  onChange,
  autoComplete,
}: {
  label: string
  type: string
  value: string
  onChange: (value: string) => void
  autoComplete?: string
}) {
  return (
    <Field label={label}>
      <Input
        type={type}
        value={value}
        autoComplete={autoComplete}
        required
        onChange={(event) => onChange(event.target.value)}
      />
    </Field>
  )
}

// Full-width accent submit — the one saturated element on the page.
export function SubmitButton({
  submitting,
  children,
  className,
}: {
  submitting: boolean
  children: ReactNode
  className?: string
}) {
  return (
    <button
      type="submit"
      disabled={submitting}
      className={cx(
        'mt-2 w-full rounded-ctl border border-accent-deep bg-accent px-4 py-2.5 text-sm font-medium text-on-accent',
        'transition duration-150 hover:brightness-105 active:translate-y-px disabled:cursor-not-allowed disabled:opacity-45',
        className,
      )}
    >
      {submitting ? 'Please wait…' : children}
    </button>
  )
}
