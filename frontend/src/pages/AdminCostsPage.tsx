import { useEffect, useState } from 'react'
import { ApiError, adminApi } from '../lib/api'
import { useAuth } from '../lib/auth'
import type { CostSummary, DailyCost, OperationCost } from '../lib/types'
import { AppShell } from '../components/AppShell'
import {
  Button,
  Empty,
  ErrorText,
  Eyebrow,
  Loading,
  Meta,
  PageTitle,
  Panel,
  Pill,
  Row,
  Rows,
  SectionHead,
} from '../components/ui'
import { cx } from '../lib/style'

const WINDOWS = [7, 30, 90] as const

// What the app is spending on AI providers, and how much of that the semantic cache is keeping
// off the bill. Admin-only — the backend enforces it, this page just avoids showing a 403.
export function AdminCostsPage() {
  const { user } = useAuth()
  const [days, setDays] = useState<number>(30)
  const [summary, setSummary] = useState<CostSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const isAdmin = user?.role === 'ADMIN'

  useEffect(() => {
    if (!isAdmin) {
      setLoading(false)
      return
    }
    let active = true
    setLoading(true)
    adminApi
      .costs(days)
      .then((result) => {
        if (active) {
          setSummary(result)
          setError(null)
        }
      })
      .catch((err) => {
        if (active) setError(err instanceof ApiError ? err.message : 'Could not load costs.')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [days, isAdmin])

  if (!isAdmin) {
    return (
      <AppShell>
        <PageTitle eyebrow="Operations" title="Spend" />
        <Empty>This page is for administrators.</Empty>
      </AppShell>
    )
  }

  return (
    <AppShell>
      <PageTitle
        eyebrow="Operations"
        title="Spend"
        sub="Every provider call this app has paid for, and what the answer cache avoided."
        action={
          <div className="flex gap-1">
            {WINDOWS.map((window) => (
              <Button
                key={window}
                size="sm"
                variant={window === days ? 'primary' : 'ghost'}
                onClick={() => setDays(window)}
              >
                {window}d
              </Button>
            ))}
          </div>
        }
      />

      {loading && <Loading />}
      {error && <ErrorText>{error}</ErrorText>}

      {summary && !loading && (
        <>
          <section className="mb-14">
            <SectionHead
              index="01 · Ledger"
              title="Totals"
              description={`Provider calls over the last ${summary.windowDays} days.`}
            />
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <Stat label="Spent" value={usd(summary.totals.costUsd)} emphasis />
              <Stat label="Calls" value={count(summary.totals.calls)} />
              <Stat label="Input tokens" value={count(summary.totals.inputTokens)} />
              <Stat label="Output tokens" value={count(summary.totals.outputTokens)} />
            </div>
          </section>

          <section className="mb-14">
            <SectionHead
              index="02 · Cache"
              title="Answers served without paying"
              description="Counted since the cache was switched on, not over the window above."
            />
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <Stat label="Hit rate" value={percent(summary.cache.hitRate)} emphasis />
              <Stat label="Saved (est.)" value={usd(summary.cache.estimatedSavedUsd)} />
              <Stat label="From cache" value={count(summary.cache.hits)} />
              <Stat label="From the model" value={count(summary.cache.answersFromModel)} />
            </div>
            <Meta className="mt-3 block">
              The saved figure is the number of cache hits times the average price of a generated
              answer — the calls it avoided were never made, so their real cost is unknowable.
            </Meta>
          </section>

          <section className="mb-14">
            <SectionHead
              index="03 · Breakdown"
              title="By feature"
              description="Most expensive first."
            />
            {summary.byOperation.length === 0 ? (
              <Empty>No provider calls in this window.</Empty>
            ) : (
              <Rows>
                {summary.byOperation.map((row) => (
                  <OperationRow key={row.operation} row={row} total={summary.totals.costUsd} />
                ))}
              </Rows>
            )}
          </section>

          <section>
            <SectionHead index="04 · Trend" title="By day" description="Days with no activity are omitted." />
            {summary.daily.length === 0 ? (
              <Empty>Nothing recorded yet.</Empty>
            ) : (
              <DailyChart daily={summary.daily} />
            )}
          </section>
        </>
      )}
    </AppShell>
  )
}

// A single number with its label. The number carries the weight, so it gets the display face and
// tabular figures; the label stays an eyebrow.
function Stat({ label, value, emphasis }: { label: string; value: string; emphasis?: boolean }) {
  return (
    <Panel className="flex flex-col gap-1">
      <Eyebrow>{label}</Eyebrow>
      <span
        className={cx(
          'tnum font-display text-[26px] leading-none font-bold tracking-[-0.02em]',
          emphasis ? 'text-accent' : 'text-ink',
        )}
      >
        {value}
      </span>
    </Panel>
  )
}

function OperationRow({ row, total }: { row: OperationCost; total: number }) {
  const share = total > 0 ? row.costUsd / total : 0
  return (
    <Row>
      <div className="flex items-center gap-4 px-5 py-3.5">
        <div className="min-w-0 flex-1">
          <p className="m-0 truncate text-[15px] font-medium text-ink">{label(row.operation)}</p>
          <Meta>
            {count(row.calls)} calls · {count(row.inputTokens)} in · {count(row.outputTokens)} out
          </Meta>
          {/* The bar makes the ordering readable at a glance; the number is the real answer. */}
          <div className="mt-2 h-1 w-full overflow-hidden rounded-full bg-surface-2">
            <div className="h-full rounded-full bg-accent" style={{ width: `${share * 100}%` }} />
          </div>
        </div>
        <div className="shrink-0 text-right">
          <span className="tnum block font-mono text-[15px] text-ink">{usd(row.costUsd)}</span>
          <Meta>{percent(share)}</Meta>
        </div>
      </div>
    </Row>
  )
}

// A plain column chart in divs. Nothing here needs a charting library: one series, a handful of
// bars, and the exact numbers are one hover away.
function DailyChart({ daily }: { daily: DailyCost[] }) {
  const peak = Math.max(...daily.map((day) => day.costUsd), 0)
  return (
    <Panel>
      <div className="flex h-40 items-end gap-1 overflow-x-auto">
        {daily.map((day) => (
          <div key={day.day} className="flex min-w-[14px] flex-1 flex-col items-center gap-1.5">
            <div
              className="w-full rounded-t-sm bg-accent transition duration-150 hover:brightness-110"
              style={{ height: `${peak > 0 ? Math.max((day.costUsd / peak) * 100, 2) : 2}%` }}
              title={`${day.day} · ${usd(day.costUsd)} · ${count(day.calls)} calls`}
            />
          </div>
        ))}
      </div>
      <div className="mt-3 flex items-center justify-between border-t border-line-soft pt-3">
        <Meta>{daily[0]?.day}</Meta>
        <Pill tone="accent">peak {usd(peak)}</Pill>
        <Meta>{daily[daily.length - 1]?.day}</Meta>
      </div>
    </Panel>
  )
}

// Spend here is fractions of a cent, so two decimals would round most of it to $0.00.
function usd(value: number): string {
  return `$${value.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 4 })}`
}

function count(value: number): string {
  return value.toLocaleString('en-US')
}

function percent(value: number): string {
  return `${(value * 100).toFixed(1)}%`
}

// The backend sends enum names; these are the ones worth a human label. Anything new falls back
// to a readable version of the constant rather than showing nothing.
const LABELS: Record<string, string> = {
  CHAT: 'Chat',
  CHAT_STREAM: 'Chat — streamed',
  SUMMARY: 'Document summaries',
  QUIZ_GENERATION: 'Quiz generation',
  QUIZ_GRADING: 'Short-answer grading',
  FLASHCARD_GENERATION: 'Flashcard generation',
  EMBED_DOCUMENTS: 'Document embeddings',
  EMBED_QUERY: 'Question embeddings',
  OTHER: 'Unlabelled',
}

function label(operation: string): string {
  return (
    LABELS[operation] ??
    operation.toLowerCase().replace(/_/g, ' ').replace(/^./, (first) => first.toUpperCase())
  )
}
