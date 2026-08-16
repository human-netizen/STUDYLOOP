import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError, coursesApi, forumApi } from '../lib/api'
import type { CourseResponse, ForumThreadStatus, ForumThreadSummary } from '../lib/types'
import { AppShell } from '../components/AppShell'
import {
  Button,
  Empty,
  ErrorText,
  Field,
  Input,
  Loading,
  Meta,
  PageTitle,
  Panel,
  Pill,
  Row,
  Rows,
  SectionHead,
  TextArea,
} from '../components/ui'

const FILTERS = [
  { label: 'All', value: null },
  { label: 'Open', value: 'OPEN' as const },
  { label: 'Answered', value: 'ANSWERED' as const },
]

// Where a refusal goes to be answered by people (Phase 9.2). Most threads arrive here from the
// Ask page — a question the assistant couldn't answer, escalated with one click — but anyone can
// also start one outright.
export function CourseForumPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()

  const [course, setCourse] = useState<CourseResponse | null>(null)
  const [threads, setThreads] = useState<ForumThreadSummary[]>([])
  const [filter, setFilter] = useState<ForumThreadStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    coursesApi
      .get(id)
      .then((data) => {
        if (active) setCourse(data)
      })
      .catch(() => {
        // The rail's label is cosmetic; the list below carries the real failure.
      })
    return () => {
      active = false
    }
  }, [id])

  useEffect(() => {
    let active = true
    setLoading(true)
    forumApi
      .list(id, filter ?? undefined)
      .then((list) => {
        if (!active) return
        setThreads(list)
        setError(null)
      })
      .catch((err) => {
        if (active) setError(err instanceof ApiError ? err.message : 'Could not load the forum.')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [id, filter])

  const openCount = threads.filter((thread) => thread.status === 'OPEN').length

  return (
    <AppShell courseName={course?.name}>
      <PageTitle
        eyebrow={course?.name ?? 'Course'}
        title="Forum"
        sub="Questions the materials couldn't answer. An accepted answer joins the course's materials, so the next person to ask gets it from the assistant."
        action={
          <div className="flex gap-1">
            {FILTERS.map((option) => (
              <Button
                key={option.label}
                size="sm"
                variant={option.value === filter ? 'primary' : 'ghost'}
                onClick={() => setFilter(option.value)}
              >
                {option.label}
              </Button>
            ))}
          </div>
        }
      />

      <section className="mb-14">
        <SectionHead index="01 · Ask" title="Put a question to the class" />
        <AskForm
          courseId={id}
          onOpened={(threadId) => navigate(`/courses/${id}/forum/${threadId}`)}
        />
      </section>

      <section>
        <SectionHead
          index="02 · Threads"
          title={filter === 'ANSWERED' ? 'Answered' : filter === 'OPEN' ? 'Still open' : 'Everything'}
          description={
            threads.length > 0 && filter === null
              ? `${threads.length} thread${threads.length === 1 ? '' : 's'} · ${openCount} still open`
              : undefined
          }
        />

        {loading && <Loading />}
        {error && <ErrorText>{error}</ErrorText>}

        {!loading && !error && threads.length === 0 && (
          <Empty>
            Nothing here yet. When the assistant can't answer something, escalate it from the Ask
            page and it lands here.
          </Empty>
        )}

        {!loading && threads.length > 0 && (
          <Rows>
            {threads.map((thread) => (
              <ThreadRow key={thread.id} courseId={id} thread={thread} />
            ))}
          </Rows>
        )}
      </section>
    </AppShell>
  )
}

function ThreadRow({ courseId, thread }: { courseId: string; thread: ForumThreadSummary }) {
  return (
    <Row interactive>
      <Link
        to={`/courses/${courseId}/forum/${thread.id}`}
        className="flex items-start justify-between gap-4 px-5 py-4 no-underline"
      >
        <div className="min-w-0">
          <p className="m-0 text-[15px] leading-snug font-medium text-ink">{thread.title}</p>
          <Meta>
            {thread.authorName} · {new Date(thread.createdAt).toLocaleDateString()} ·{' '}
            {thread.answerCount} {thread.answerCount === 1 ? 'reply' : 'replies'}
            {thread.fromRefusal && ' · from a refusal'}
          </Meta>
        </div>
        <div className="flex shrink-0 flex-col items-end gap-1.5">
          <Pill tone={thread.status === 'ANSWERED' ? 'ok' : 'warn'}>
            {thread.status === 'ANSWERED' ? 'Answered' : 'Open'}
          </Pill>
          {/* The loop, stated where it can be seen: this answer is corpus now. */}
          {thread.inCorpus && <Pill tone="accent">In materials</Pill>}
        </div>
      </Link>
    </Row>
  )
}

function AskForm({
  courseId,
  onOpened,
}: {
  courseId: string
  onOpened: (threadId: string) => void
}) {
  const [title, setTitle] = useState('')
  const [body, setBody] = useState('')
  const [posting, setPosting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function open() {
    const question = title.trim()
    if (!question || posting) return
    setError(null)
    setPosting(true)
    try {
      const thread = await forumApi.open(courseId, { title: question, body: body.trim() || null })
      onOpened(thread.id)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not post that question.')
      setPosting(false)
    }
  }

  return (
    <Panel className="flex flex-col gap-4">
      <Field label="Question">
        <Input
          value={title}
          maxLength={300}
          placeholder="What are you stuck on?"
          disabled={posting}
          onChange={(event) => setTitle(event.target.value)}
        />
      </Field>
      <Field label="Details (optional)">
        <TextArea
          value={body}
          rows={3}
          maxLength={4000}
          placeholder="What you've tried, where it stops making sense…"
          disabled={posting}
          onChange={(event) => setBody(event.target.value)}
        />
      </Field>
      <div className="flex items-center justify-between gap-4">
        <Meta>Everyone in the course can see and answer this.</Meta>
        <Button variant="primary" onClick={() => void open()} disabled={posting || !title.trim()}>
          {posting ? 'Posting…' : 'Ask'}
        </Button>
      </div>
      {error && <ErrorText>{error}</ErrorText>}
    </Panel>
  )
}
