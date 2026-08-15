import type { ReactNode } from 'react'
import { Link, NavLink, useParams } from 'react-router-dom'
import { useAuth } from '../lib/auth'
import { Button, Eyebrow } from './ui'
import { cx } from '../lib/style'

// The frame every signed-in page sits in: a sticky 232px rail on the left holding the brand,
// the navigation, and the account block; the page itself in the column beside it.
//
// Below 1024px the rail unsticks and becomes a top bar whose nav scrolls sideways, so the
// same markup serves both without a second component.

// `fill` is for pages that manage their own scrolling (chat): main becomes a fixed-height
// flex column instead of a growing document.
export function AppShell({
  children,
  courseName,
  fill = false,
}: {
  children: ReactNode
  courseName?: string
  fill?: boolean
}) {
  return (
    <div className="mx-auto flex min-h-screen max-w-[1400px] flex-col lg:grid lg:grid-cols-[232px_minmax(0,1fr)] lg:items-start">
      <Rail courseName={courseName} />
      <main
        className={cx(
          'min-w-0 px-5 pt-8 pb-20 sm:px-8 lg:px-12 lg:pt-14 lg:pb-32',
          fill && 'flex flex-1 flex-col overflow-hidden lg:h-screen lg:pt-10 lg:pb-10',
        )}
      >
        {children}
      </main>
    </div>
  )
}

function Rail({ courseName }: { courseName?: string }) {
  const { id: courseId } = useParams()
  const { user, logout } = useAuth()

  return (
    <aside className="flex flex-col gap-6 border-b border-line bg-ground-2 px-5 py-6 sm:px-8 lg:sticky lg:top-0 lg:h-screen lg:self-start lg:overflow-y-auto lg:border-r lg:border-b-0 lg:py-8 lg:pr-5 lg:pl-7">
      <Link to="/" className="no-underline">
        <span className="block font-display text-[19px] leading-[1.05] font-bold tracking-[-0.03em] text-ink">
          Study
          <br className="hidden lg:block" />
          Loop
        </span>
        <Eyebrow className="mt-1.5">Your materials, answering back</Eyebrow>
      </Link>

      <nav className="-mx-1 flex gap-1 overflow-x-auto lg:mx-0 lg:flex-col lg:overflow-visible">
        <RailLink to="/" index="01" label="Your courses" end />
        {courseId && (
          <>
            <div className="hidden pt-4 pb-1 lg:block">
              <Eyebrow className="truncate px-1.5">{courseName ?? 'Course'}</Eyebrow>
            </div>
            <RailLink to={`/courses/${courseId}`} index="02" label="Overview" end />
            <RailLink to={`/courses/${courseId}/chat`} index="03" label="Ask" />
            <RailLink to={`/courses/${courseId}/quizzes`} index="04" label="Quizzes" />
            <RailLink to={`/courses/${courseId}/flashcards`} index="05" label="Flashcards" />
          </>
        )}
      </nav>

      <div className="mt-auto hidden border-t border-line pt-4 lg:block">
        <p className="m-0 truncate text-[13px] font-medium text-ink">{user?.displayName}</p>
        <p className="tnum m-0 truncate font-mono text-[10.5px] text-ink-muted">{user?.email}</p>
        <Button variant="ghost" size="sm" onClick={logout} className="mt-3 w-full">
          Sign out
        </Button>
      </div>

      {/* Mobile: the account block can't sit at the bottom of a horizontal bar, so it rides
          on the right of the brand row instead. */}
      <div className="flex items-center justify-between gap-3 border-t border-line pt-3 lg:hidden">
        <span className="tnum truncate font-mono text-[10.5px] text-ink-muted">{user?.email}</span>
        <Button variant="ghost" size="sm" onClick={logout}>
          Sign out
        </Button>
      </div>
    </aside>
  )
}

function RailLink({
  to,
  index,
  label,
  end,
}: {
  to: string
  index: string
  label: string
  end?: boolean
}) {
  return (
    <NavLink
      to={to}
      end={end}
      className={({ isActive }) =>
        cx(
          'flex shrink-0 items-baseline gap-2.5 rounded-ctl border-l-2 px-2 py-1.5 text-[13px] no-underline transition duration-150',
          isActive
            ? 'border-l-accent bg-surface font-medium text-ink'
            : 'border-l-transparent text-ink-2 hover:bg-surface hover:text-ink',
        )
      }
    >
      <span className="tnum font-mono text-[10.5px] text-ink-muted">{index}</span>
      {label}
    </NavLink>
  )
}

// A back-link that reads as navigation rather than as body text.
export function BackLink({ to, children }: { to: string; children: ReactNode }) {
  return (
    <Link
      to={to}
      className="mb-6 inline-flex items-center gap-2 font-mono text-[11px] tracking-[0.08em] text-ink-muted uppercase no-underline transition duration-150 hover:text-ink"
    >
      <span aria-hidden>←</span>
      {children}
    </Link>
  )
}
