import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError, coursesApi, reviewApi } from '../lib/api'
import type { CourseResponse, ReviewCard, ReviewResult } from '../lib/types'
import { AppShell } from '../components/AppShell'
import { Button, Empty, ErrorText, Eyebrow, Loading, Meta, PageTitle, Pill } from '../components/ui'
import { cx } from '../lib/style'

// The four grades a person can actually tell apart, mapped onto SM-2's 0-5 scale. The number is
// printed on the button because the scale is the algorithm's, not ours — anything under 3 is a
// lapse and restarts the card.
const GRADES = [
  { grade: 1, label: 'Again', hint: 'No idea', tone: 'bad' as const },
  { grade: 3, label: 'Hard', hint: 'Right, barely', tone: 'warn' as const },
  { grade: 4, label: 'Good', hint: 'Right, a pause', tone: 'neutral' as const },
  { grade: 5, label: 'Easy', hint: 'Instant', tone: 'ok' as const },
]

// Today's spaced-repetition queue. One card at a time: read the front, commit to an answer,
// reveal the back, then say how it went. Grading reschedules the card and drops it from the
// queue, so the list only ever shrinks within a session.
export function ReviewPage() {
  // Present on /courses/:id/review, absent on the global /review.
  const { id: courseId } = useParams()

  const [course, setCourse] = useState<CourseResponse | null>(null)
  const [cards, setCards] = useState<ReviewCard[]>([])
  const [position, setPosition] = useState(0)
  const [revealed, setRevealed] = useState(false)
  const [grading, setGrading] = useState(false)
  const [lastResult, setLastResult] = useState<ReviewResult | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    const load = courseId
      ? Promise.all([coursesApi.get(courseId), reviewApi.queue(courseId)])
      : Promise.all([Promise.resolve(null), reviewApi.queue()])

    load
      .then(([courseData, queue]) => {
        if (!active) return
        setCourse(courseData)
        setCards(queue)
      })
      .catch((err) => {
        if (active) setError(err instanceof ApiError ? err.message : 'Failed to load your queue.')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [courseId])

  const card = cards[position]
  const done = !loading && !error && position >= cards.length

  const grade = useCallback(
    async (value: number) => {
      if (!card || grading) return
      setGrading(true)
      setError(null)
      try {
        const result = await reviewApi.grade(card.cardId, value)
        setLastResult(result)
        setRevealed(false)
        setPosition((current) => current + 1)
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Could not save that answer.')
      } finally {
        setGrading(false)
      }
    },
    [card, grading],
  )

  // Space reveals the back; 1-4 grade it. Keeping hands off the mouse is most of what makes a
  // review session bearable.
  useEffect(() => {
    if (!card) return
    function onKey(event: KeyboardEvent) {
      if (event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement) return
      if (event.code === 'Space' || event.key === 'Enter') {
        event.preventDefault()
        if (!revealed) setRevealed(true)
        return
      }
      if (!revealed) return
      const slot = Number(event.key)
      if (slot >= 1 && slot <= GRADES.length) {
        event.preventDefault()
        void grade(GRADES[slot - 1].grade)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [card, revealed, grade])

  const reviewed = Math.min(position, cards.length)

  return (
    <AppShell courseName={course?.name}>
      <PageTitle
        eyebrow={course?.name ?? 'All courses'}
        title="Today's review"
        sub={
          cards.length > 0
            ? 'Answer in your head first, then reveal. Grade honestly — the schedule is only as good as the grades.'
            : undefined
        }
        action={
          cards.length > 0 ? (
            <Pill tone="accent">
              <span className="tnum">
                {reviewed} / {cards.length}
              </span>
            </Pill>
          ) : undefined
        }
      />

      {loading && <Loading>Loading your queue</Loading>}
      {error && <ErrorText>{error}</ErrorText>}

      {!loading && !error && cards.length === 0 && (
        <Empty>
          Nothing is due right now. Cards arrive here when you make them, and again whenever a
          quiz catches you out.
        </Empty>
      )}

      {done && cards.length > 0 && <SessionComplete count={cards.length} last={lastResult} />}

      {card && (
        <>
          <Progress reviewed={reviewed} total={cards.length} />

          <div className="mt-6 rounded-card border border-line bg-surface shadow-card">
            <div className="flex items-center justify-between gap-3 border-b border-line-soft px-5 py-3">
              <Eyebrow>{card.courseName}</Eyebrow>
              <ScheduleMeta card={card} />
            </div>

            <div className="px-5 py-8 sm:px-8 sm:py-12">
              <Eyebrow className="mb-3">Prompt</Eyebrow>
              <p className="m-0 font-display text-[clamp(20px,2.4vw,28px)] leading-[1.2] tracking-[-0.02em] text-ink">
                {card.front}
              </p>

              {revealed && (
                <div className="mt-8 border-t border-line pt-6">
                  <Eyebrow className="mb-3">Answer</Eyebrow>
                  <p className="m-0 text-[15.5px] whitespace-pre-wrap text-ink-2">{card.back}</p>
                </div>
              )}
            </div>

            <div className="border-t border-line px-5 py-4">
              {!revealed ? (
                <div className="flex items-center justify-between gap-4">
                  <Button variant="primary" onClick={() => setRevealed(true)}>
                    Show answer
                  </Button>
                  <Meta>Space</Meta>
                </div>
              ) : (
                <div className="flex flex-col gap-3">
                  <Eyebrow>How well did you recall it?</Eyebrow>
                  <div className="grid gap-2 sm:grid-cols-4">
                    {GRADES.map((option, index) => (
                      <GradeButton
                        key={option.grade}
                        {...option}
                        slot={index + 1}
                        disabled={grading}
                        onClick={() => void grade(option.grade)}
                      />
                    ))}
                  </div>
                </div>
              )}
            </div>
          </div>

          {lastResult && <LastResult result={lastResult} />}
        </>
      )}
    </AppShell>
  )
}

// A hairline bar rather than a percentage — the count is already in the page title.
function Progress({ reviewed, total }: { reviewed: number; total: number }) {
  const pct = total === 0 ? 0 : Math.round((reviewed / total) * 100)
  return (
    <div className="h-[3px] overflow-hidden rounded-full bg-ground-2">
      <div
        className="h-full rounded-full bg-accent transition-all duration-500"
        style={{ width: `${pct}%` }}
      />
    </div>
  )
}

// The card's standing in the schedule: how overdue it is, and whether it's been failed before.
function ScheduleMeta({ card }: { card: ReviewCard }) {
  const overdue = daysBetween(card.dueOn, todayIso())
  return (
    <div className="flex items-center gap-2">
      {card.repetitions === 0 && card.lapses === 0 && <Pill>New</Pill>}
      {card.lapses > 0 && (
        <Pill tone="warn">
          <span className="tnum">{card.lapses}</span> lapse{card.lapses === 1 ? '' : 's'}
        </Pill>
      )}
      {overdue > 0 && (
        <Meta>
          <span className="tnum">{overdue}</span>d overdue
        </Meta>
      )}
    </div>
  )
}

function GradeButton({
  grade,
  label,
  hint,
  tone,
  slot,
  disabled,
  onClick,
}: {
  grade: number
  label: string
  hint: string
  tone: 'ok' | 'warn' | 'bad' | 'neutral'
  slot: number
  disabled: boolean
  onClick: () => void
}) {
  const border = {
    ok: 'hover:border-ok',
    warn: 'hover:border-warn',
    bad: 'hover:border-bad',
    neutral: 'hover:border-line-strong',
  }[tone]

  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={cx(
        'flex cursor-pointer flex-col items-start gap-0.5 rounded-ctl border border-line bg-surface-2 px-3 py-2 text-left',
        'transition duration-150 hover:bg-surface active:translate-y-px disabled:cursor-not-allowed disabled:opacity-45',
        border,
      )}
    >
      <span className="flex w-full items-baseline justify-between gap-2">
        <span className="text-sm font-medium text-ink">{label}</span>
        <span className="tnum font-mono text-[10.5px] text-ink-muted">{grade}</span>
      </span>
      <span className="font-mono text-[10.5px] tracking-[0.06em] text-ink-muted uppercase">
        {hint} · {slot}
      </span>
    </button>
  )
}

// What the previous grade did to that card's schedule — the feedback that makes the algorithm
// visible rather than magic.
function LastResult({ result }: { result: ReviewResult }) {
  return (
    <p className="mt-4 mb-0 font-mono text-[11.5px] tracking-[0.06em] text-ink-muted uppercase">
      {result.lapsed ? 'Lapsed — back tomorrow' : `Next in ${result.intervalDays}d`} ·{' '}
      <span className="tnum">ease {result.easeFactor.toFixed(2)}</span> ·{' '}
      <span className="tnum">{result.remainingToday}</span> left today
    </p>
  )
}

function SessionComplete({ count, last }: { count: number; last: ReviewResult | null }) {
  return (
    <div className="rounded-card border border-ok/40 bg-ok-bg px-6 py-10 text-center">
      <p className="m-0 font-display text-[26px] tracking-[-0.02em] text-ink">Queue cleared</p>
      <p className="m-0 mt-2 text-sm text-ink-2">
        <span className="tnum">{count}</span> card{count === 1 ? '' : 's'} reviewed.
        {last && last.remainingToday > 0 && (
          <>
            {' '}
            <span className="tnum">{last.remainingToday}</span> still due in your other courses —{' '}
            <Link to="/review" className="text-ink underline decoration-accent underline-offset-4">
              keep going
            </Link>
            .
          </>
        )}
      </p>
    </div>
  )
}

function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

// Whole days between two yyyy-mm-dd strings. Both are dates, so parsing them as UTC midnight
// avoids the off-by-one a local-timezone parse would give.
function daysBetween(from: string, to: string): number {
  const ms = Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)
  return Math.round(ms / 86_400_000)
}
