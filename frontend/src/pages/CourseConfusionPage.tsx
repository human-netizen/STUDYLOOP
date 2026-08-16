import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError, analyticsApi, coursesApi, forumApi } from '../lib/api'
import type { ConfusionReport, LectureHeat, TopicCluster, UngroundedQuestion } from '../lib/types'
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

// What the class is stuck on (Phase 9.1). Anonymized by construction: the backend records who
// asked so it can count students rather than questions, and then never sends it.
//
// Instructors and owners only. The rail hides the link for a plain MEMBER, but a pasted URL has
// to land somewhere sensible too, so a 403 renders as a message rather than an error.
export function CourseConfusionPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const [courseName, setCourseName] = useState<string | undefined>()
  const [days, setDays] = useState<number>(30)
  const [report, setReport] = useState<ConfusionReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [forbidden, setForbidden] = useState(false)

  useEffect(() => {
    let active = true
    coursesApi
      .get(id)
      .then((course) => {
        if (active) setCourseName(course.name)
      })
      .catch(() => {
        // The rail's course label is cosmetic; the report below carries the real failure.
      })
    return () => {
      active = false
    }
  }, [id])

  useEffect(() => {
    let active = true
    setLoading(true)
    analyticsApi
      .confusion(id, days)
      .then((result) => {
        if (!active) return
        setReport(result)
        setError(null)
        setForbidden(false)
      })
      .catch((err) => {
        if (!active) return
        if (err instanceof ApiError && err.status === 403) {
          setForbidden(true)
          return
        }
        setError(err instanceof ApiError ? err.message : 'Could not load the report.')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [id, days])

  if (forbidden) {
    return (
      <AppShell courseName={courseName}>
        <PageTitle eyebrow="Teaching" title="Confusion" />
        <Empty>This page is for the people running the course.</Empty>
      </AppShell>
    )
  }

  return (
    <AppShell courseName={courseName}>
      <PageTitle
        eyebrow="Teaching"
        title="Confusion"
        sub="What the class asked, what it kept asking, and what your materials could not answer."
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

      {report && !loading && (
        <>
          <section className="mb-14">
            <SectionHead
              index="01 · Volume"
              title="Totals"
              description={`Questions asked in this course over the last ${report.windowDays} days.`}
            />
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <Stat label="Questions" value={count(report.totals.questionsAsked)} />
              <Stat label="Students asking" value={count(report.totals.distinctAskers)} />
              <Stat
                label="Unanswerable"
                value={count(report.totals.ungrounded)}
                emphasis={report.totals.ungrounded > 0}
              />
              <Stat label="Miss rate" value={percent(report.totals.ungroundedRate)} />
            </div>
            <Meta className="mt-3 block">
              A question counts as unanswerable when the confidence gate refused it — nothing in
              the corpus matched closely enough to answer from. That is a gap in the materials or
              in retrieval, not a student mistake.
            </Meta>
          </section>

          <section className="mb-14">
            <SectionHead
              index="02 · Heatmap"
              title="By lecture"
              description="Where the questions landed. Every document appears, including the quiet ones."
            />
            {report.lectures.length === 0 ? (
              <Empty>No documents in this course yet.</Empty>
            ) : (
              <Rows>
                {report.lectures.map((lecture) => (
                  <LectureRow key={lecture.documentId} lecture={lecture} />
                ))}
              </Rows>
            )}
            <Meta className="mt-3 block">
              A lecture with zero questions is a finding too: either nobody has reached it, or
              retrieval never surfaces it.
            </Meta>
          </section>

          <section className="mb-14">
            <SectionHead
              index="03 · Topics"
              title="Asked more than once"
              description="Questions grouped by meaning, not by wording. The heading is a real question from the group."
            />
            {report.topics.length === 0 ? (
              <Empty>Not enough questions yet to find a pattern.</Empty>
            ) : (
              <div className="grid gap-3">
                {report.topics.map((topic) => (
                  <TopicCard key={topic.label} topic={topic} />
                ))}
              </div>
            )}
            {report.clustersComputedAt && (
              <Meta className="mt-3 block">
                Grouped {new Date(report.clustersComputedAt).toLocaleString()}.
              </Meta>
            )}
          </section>

          <section>
            <SectionHead
              index="04 · Gaps"
              title="Nothing in the materials answered these"
              description="Newest first. The score is the closest the corpus got."
            />
            {report.ungrounded.length === 0 ? (
              <Empty>Every question so far was answerable from the materials.</Empty>
            ) : (
              <Rows>
                {report.ungrounded.map((item) => (
                  <UngroundedRow
                    key={item.questionEventId}
                    courseId={id}
                    item={item}
                    onEscalated={(threadId) => navigate(`/courses/${id}/forum/${threadId}`)}
                  />
                ))}
              </Rows>
            )}
            <Meta className="mt-3 block">
              Escalating one opens a thread the class can answer. Accept an answer there and it
              becomes course material — the same question stops being a gap.
            </Meta>
          </section>
        </>
      )}
    </AppShell>
  )
}

function Stat({ label, value, emphasis }: { label: string; value: string; emphasis?: boolean }) {
  return (
    <Panel className="flex flex-col gap-1">
      <Eyebrow>{label}</Eyebrow>
      <span
        className={cx(
          'tnum font-display text-[26px] leading-none font-bold tracking-[-0.02em]',
          emphasis ? 'text-bad' : 'text-ink',
        )}
      >
        {value}
      </span>
    </Panel>
  )
}

// The heat is the bar. A count alone can't be compared across courses of different sizes, and a
// bar alone can't be quoted in a meeting — so both.
function LectureRow({ lecture }: { lecture: LectureHeat }) {
  const quiet = lecture.questionCount === 0
  return (
    <Row>
      <div className="flex items-center gap-4 px-5 py-3.5">
        <div className="min-w-0 flex-1">
          <p className="m-0 truncate text-[15px] font-medium text-ink">{lecture.filename}</p>
          <Meta>
            {quiet
              ? 'no questions in this window'
              : `${count(lecture.distinctAskers)} ${plural(lecture.distinctAskers, 'student')} · last asked ${shortDate(lecture.lastAskedAt)}`}
          </Meta>
          <div className="mt-2 h-1 w-full overflow-hidden rounded-full bg-surface-2">
            <div
              className="h-full rounded-full bg-accent"
              style={{ width: `${lecture.share * 100}%` }}
            />
          </div>
        </div>
        <div className="shrink-0 text-right">
          <span className={cx('tnum block font-mono text-[15px]', quiet ? 'text-ink-muted' : 'text-ink')}>
            {count(lecture.questionCount)}
          </span>
          <Meta>{percent(lecture.share)}</Meta>
        </div>
      </div>
    </Row>
  )
}

function TopicCard({ topic }: { topic: TopicCluster }) {
  // A group the corpus answered none of is the strongest signal on the page — the class keeps
  // asking something the materials do not cover.
  const allMissed = topic.ungroundedCount === topic.questionCount
  return (
    <Panel className="flex flex-col gap-2.5">
      <div className="flex items-start justify-between gap-4">
        <p className="m-0 text-[15px] leading-snug font-medium text-ink">“{topic.label}”</p>
        <Pill tone={allMissed ? 'bad' : 'accent'}>
          {topic.questionCount}×
        </Pill>
      </div>
      <Meta>
        {count(topic.distinctAskers)} {plural(topic.distinctAskers, 'student')}
        {topic.ungroundedCount > 0 &&
          ` · ${count(topic.ungroundedCount)} unanswered${allMissed ? ' — none of them' : ''}`}
        {' · last '}
        {shortDate(topic.lastAskedAt)}
      </Meta>
      {topic.lectures.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {topic.lectures.map((lecture) => (
            <Pill key={lecture.documentId}>
              {lecture.filename} · {lecture.questionCount}
            </Pill>
          ))}
        </div>
      )}
    </Panel>
  )
}

function UngroundedRow({
  courseId,
  item,
  onEscalated,
}: {
  courseId: string
  item: UngroundedQuestion
  onEscalated: (threadId: string) => void
}) {
  const [posting, setPosting] = useState(false)
  const [failed, setFailed] = useState(false)

  async function escalate() {
    setPosting(true)
    setFailed(false)
    try {
      const thread = await forumApi.open(courseId, {
        title: item.question,
        questionEventId: item.questionEventId,
      })
      onEscalated(thread.id)
    } catch {
      setFailed(true)
      setPosting(false)
    }
  }

  return (
    <Row>
      <div className="flex items-start gap-4 px-5 py-3.5">
        <div className="min-w-0 flex-1">
          <p className="m-0 text-[15px] leading-snug text-ink">{item.question}</p>
          <Meta>{shortDate(item.askedAt)}</Meta>
          {/* Already a discussion, or an offer to make it one. Either way the row stops being
              only a complaint. */}
          <div className="mt-1.5">
            {item.threadId ? (
              <Link
                to={`/courses/${courseId}/forum/${item.threadId}`}
                className="font-mono text-[11px] tracking-[0.06em] text-accent uppercase no-underline hover:underline"
              >
                Discussion →
              </Link>
            ) : (
              <Button variant="quiet" onClick={() => void escalate()} disabled={posting}>
                {failed ? 'Could not open — retry' : posting ? 'Opening…' : '+ Ask the class'}
              </Button>
            )}
          </div>
        </div>
        {/* A near miss is worth a clarifying paragraph; a very low score usually means the
            question was off-topic and the gate did its job. */}
        <Pill tone={near(item.topSimilarity) ? 'warn' : 'neutral'}>
          {item.topSimilarity == null ? 'no match' : item.topSimilarity.toFixed(2)}
        </Pill>
      </div>
    </Row>
  )
}

// The gate's threshold is 0.25; anything from 0.15 up was close enough that a small addition to
// the materials would likely have carried it over.
function near(similarity: number | null): boolean {
  return similarity != null && similarity >= 0.15
}

function count(value: number): string {
  return value.toLocaleString('en-US')
}

function percent(value: number): string {
  return `${(value * 100).toFixed(1)}%`
}

function plural(value: number, word: string): string {
  return value === 1 ? word : `${word}s`
}

function shortDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}
