import type {
  ButtonHTMLAttributes,
  InputHTMLAttributes,
  ReactNode,
  SelectHTMLAttributes,
  TextareaHTMLAttributes,
} from 'react'
import {
  BUTTON_BASE,
  BUTTON_VARIANT,
  CONTROL,
  PANEL,
  TONE,
  cx,
  type ButtonSize,
  type ButtonVariant,
  type Tone,
} from '../lib/style'

// The shared vocabulary for the whole app. Every page composes from these instead of
// re-typing a nine-class utility string, which is how the old UI drifted: the same "card"
// was written eight slightly different ways. The class strings themselves live in
// ../lib/style so this file exports components only (Fast Refresh).

/* ── type ─────────────────────────────────────────────────────────────────── */

// The small uppercase label that sits above a title. Used on section heads, card
// fronts/backs, and any "what am I looking at" line.
export function Eyebrow({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <span
      className={cx(
        'block font-mono text-[10.5px] font-medium tracking-[0.1em] text-ink-muted uppercase',
        className,
      )}
    >
      {children}
    </span>
  )
}

// Small mono meta text: counts, sizes, dates, filenames. Tabular figures so numbers in a
// list line up column-wise.
export function Meta({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <span className={cx('tnum font-mono text-[11.5px] text-ink-muted', className)}>{children}</span>
  )
}

// A section opener: mono number, display title, optional one-line description, sitting under
// a 2px rule. This single device carries most of the page rhythm. The rule is line-strong
// rather than full ink — at 2px across the page, near-white on this ground glares.
export function SectionHead({
  index,
  title,
  description,
  action,
}: {
  index: string
  title: string
  description?: string
  action?: ReactNode
}) {
  return (
    <div className="mb-6 border-t-2 border-line-strong pt-3">
      <div className="flex items-end justify-between gap-4">
        <div className="min-w-0">
          <Eyebrow className="mb-1.5">{index}</Eyebrow>
          <h2 className="m-0 text-[27px] leading-[1.12] tracking-[-0.025em]">{title}</h2>
          {description && <p className="mt-1 mb-0 text-sm text-ink-muted">{description}</p>}
        </div>
        {action && <div className="shrink-0 pb-1">{action}</div>}
      </div>
    </div>
  )
}

// The page's own H1 block: eyebrow + oversized display title + optional sub-line.
export function PageTitle({
  eyebrow,
  title,
  sub,
  action,
}: {
  eyebrow: string
  title: string
  sub?: ReactNode
  action?: ReactNode
}) {
  return (
    <div className="mb-10 flex flex-wrap items-end justify-between gap-6">
      <div className="min-w-0">
        <Eyebrow className="mb-2">{eyebrow}</Eyebrow>
        <h1 className="m-0 text-[clamp(34px,4.4vw,52px)] leading-[0.98] tracking-[-0.035em]">
          {title}
        </h1>
        {sub && <div className="mt-2 max-w-[62ch] text-ink-2">{sub}</div>}
      </div>
      {action && <div className="shrink-0 pb-1">{action}</div>}
    </div>
  )
}

/* ── buttons ──────────────────────────────────────────────────────────────── */

export function Button({
  variant = 'ghost',
  size = 'md',
  className,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant; size?: ButtonSize }) {
  const pad =
    variant === 'quiet'
      ? 'text-[13px]'
      : size === 'sm'
        ? 'px-3 py-1.5 text-[13px]'
        : 'px-4 py-2 text-sm'
  return (
    <button
      type="button"
      {...props}
      className={cx(BUTTON_BASE, BUTTON_VARIANT[variant], pad, className)}
    />
  )
}

/* ── containers ───────────────────────────────────────────────────────────── */

export function Panel({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cx(PANEL, className)}>{children}</div>
}

// A bordered stack — one box, hairline dividers — instead of a run of floating cards with
// gaps between them. Lists of courses, documents and quizzes all use this.
export function Rows({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <ul className={cx('m-0 list-none overflow-hidden rounded-card border border-line bg-surface p-0', className)}>
      {children}
    </ul>
  )
}

export function Row({
  children,
  className,
  interactive,
}: {
  children: ReactNode
  className?: string
  interactive?: boolean
}) {
  return (
    <li
      className={cx(
        'border-b border-line-soft last:border-b-0',
        interactive && 'transition duration-150 hover:bg-surface-2',
        className,
      )}
    >
      {children}
    </li>
  )
}

/* ── form controls ────────────────────────────────────────────────────────── */

export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={cx(CONTROL, className)} />
}

export function TextArea({ className, ...props }: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea {...props} className={cx(CONTROL, 'resize-y', className)} />
}

export function Select({ className, ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...props} className={cx(CONTROL, className)} />
}

// A labelled control. The label is an eyebrow so forms match the section rhythm.
export function Field({
  label,
  children,
  className,
}: {
  label: string
  children: ReactNode
  className?: string
}) {
  return (
    <label className={cx('flex flex-col gap-1.5', className)}>
      <Eyebrow>{label}</Eyebrow>
      {children}
    </label>
  )
}

// Bounded integer input — used for "how many questions / how many cards".
export function NumberField({
  label,
  value,
  min,
  max,
  disabled,
  onChange,
}: {
  label: string
  value: number
  min: number
  max: number
  disabled?: boolean
  onChange: (value: number) => void
}) {
  return (
    <Field label={label} className="w-24 shrink-0">
      <Input
        type="number"
        min={min}
        max={max}
        value={value}
        disabled={disabled}
        className="tnum font-mono"
        onChange={(event) => {
          const next = Number(event.target.value)
          onChange(Number.isFinite(next) ? Math.min(max, Math.max(min, next)) : min)
        }}
      />
    </Field>
  )
}

/* ── status ───────────────────────────────────────────────────────────────── */

// A mono chip: roles, document statuses, quiz verdicts.
export function Pill({
  tone = 'neutral',
  children,
  className,
}: {
  tone?: Tone
  children: ReactNode
  className?: string
}) {
  return (
    <span
      className={cx(
        'inline-flex shrink-0 items-center gap-1.5 rounded-full border px-2.5 py-0.5',
        'font-mono text-[10.5px] font-medium tracking-[0.06em] uppercase',
        TONE[tone],
        className,
      )}
    >
      {children}
    </span>
  )
}

export function ErrorText({ children, className }: { children: ReactNode; className?: string }) {
  return <p className={cx('text-sm text-bad', className)}>{children}</p>
}

// The one loading treatment: a mono line, not a spinner. Keeps the page from jumping.
export function Loading({ children = 'Loading' }: { children?: string }) {
  return (
    <p className="font-mono text-[12px] tracking-[0.08em] text-ink-muted uppercase">{children}…</p>
  )
}

// Shown where a list would be. Never a bare sentence in body text — it gets a dashed frame
// so an empty page still reads as a designed state.
export function Empty({ children }: { children: ReactNode }) {
  return (
    <div className="rounded-card border border-dashed border-line px-6 py-10 text-center text-sm text-ink-muted">
      {children}
    </div>
  )
}
