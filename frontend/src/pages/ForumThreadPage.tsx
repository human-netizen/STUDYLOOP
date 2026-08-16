import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { ApiError, coursesApi, forumApi } from '../lib/api'
import type { ForumAnswer, ForumThreadDetail } from '../lib/types'
import { AppShell, BackLink } from '../components/AppShell'
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
  SectionHead,
  TextArea,
} from '../components/ui'
import { cx } from '../lib/style'

// One discussion. Any member can reply; accepting a reply is manager-only, because accepting is
// what writes the answer into the course's corpus — the backend decides that and says so in
// `canAccept`, so this page never has to reason about roles itself.
export function ForumThreadPage() {
  const { id = '', threadId = '' } = useParams()

  const [courseName, setCourseName] = useState<string | undefined>()
  const [thread, setThread] = useState<ForumThreadDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    coursesApi
      .get(id)
      .then((course) => {
        if (active) setCourseName(course.name)
      })
      .catch(() => {
        // Cosmetic — the thread below carries the real failure.
      })
    return () => {
      active = false
    }
  }, [id])

  useEffect(() => {
    let active = true
    setLoading(true)
    forumApi
      .get(id, threadId)
      .then((data) => {
        if (!active) return
        setThread(data)
        setError(null)
      })
      .catch((err) => {
        if (active) setError(err instanceof ApiError ? err.message : 'Could not load this discussion.')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [id, threadId])

  return (
    <AppShell courseName={courseName}>
      <BackLink to={`/courses/${id}/forum`}>Forum</BackLink>

      {loading && <Loading />}
      {error && <ErrorText>{error}</ErrorText>}

      {thread && (
        <>
          <PageTitle
            eyebrow={thread.status === 'ANSWERED' ? 'Answered' : 'Open question'}
            title={thread.title}
            sub={
              <Meta>
                asked by {thread.authorName} · {new Date(thread.createdAt).toLocaleDateString()}
                {thread.questionEventId && ' · escalated from a question the assistant refused'}
              </Meta>
            }
          />

          {thread.body && (
            <Panel className="mb-8 text-sm whitespace-pre-wrap text-ink-2">{thread.body}</Panel>
          )}

          {thread.inCorpus && (
            <Panel className="mb-8 border-accent-deep">
              <Eyebrow className="mb-1.5">In the course materials</Eyebrow>
              <p className="m-0 text-sm text-ink-2">
                The accepted answer is part of this course's corpus now. Ask the assistant this
                question again and it will answer from it, with this discussion as the source.
              </p>
            </Panel>
          )}

          <section>
            <SectionHead
              index="01 · Replies"
              title={thread.answers.length === 1 ? '1 reply' : `${thread.answers.length} replies`}
              description={
                thread.canAccept && thread.answers.length > 0
                  ? 'Accepting a reply adds it to the course materials, where every future answer can cite it.'
                  : undefined
              }
            />
            {thread.answers.length === 0 ? (
              <Empty>No replies yet. If you know this one, answer it below.</Empty>
            ) : (
              <div className="grid gap-3">
                {thread.answers.map((answer) => (
                  <AnswerCard
                    key={answer.id}
                    courseId={id}
                    threadId={threadId}
                    answer={answer}
                    canAccept={thread.canAccept}
                    onAccepted={setThread}
                  />
                ))}
              </div>
            )}
          </section>

          <section className="mt-10">
            <SectionHead index="02 · Answer" title="Add a reply" />
            <ReplyForm courseId={id} threadId={threadId} onPosted={setThread} />
          </section>
        </>
      )}
    </AppShell>
  )
}

function AnswerCard({
  courseId,
  threadId,
  answer,
  canAccept,
  onAccepted,
}: {
  courseId: string
  threadId: string
  answer: ForumAnswer
  canAccept: boolean
  onAccepted: (thread: ForumThreadDetail) => void
}) {
  const [accepting, setAccepting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function accept() {
    setError(null)
    setAccepting(true)
    try {
      onAccepted(await forumApi.accept(courseId, threadId, answer.id))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not accept that answer.')
    } finally {
      setAccepting(false)
    }
  }

  return (
    <Panel className={cx('flex flex-col gap-3', answer.accepted && 'border-accent-deep')}>
      <div className="flex items-start justify-between gap-4">
        <Meta>
          {answer.authorName} · {new Date(answer.createdAt).toLocaleDateString()}
        </Meta>
        {answer.accepted && <Pill tone="ok">Accepted</Pill>}
      </div>
      <p className="m-0 text-sm leading-relaxed whitespace-pre-wrap text-ink-2">{answer.body}</p>
      {canAccept && !answer.accepted && (
        <div>
          <Button size="sm" onClick={() => void accept()} disabled={accepting}>
            {accepting ? 'Adding to materials…' : 'Accept & add to materials'}
          </Button>
        </div>
      )}
      {error && <ErrorText>{error}</ErrorText>}
    </Panel>
  )
}

function ReplyForm({
  courseId,
  threadId,
  onPosted,
}: {
  courseId: string
  threadId: string
  onPosted: (thread: ForumThreadDetail) => void
}) {
  const [body, setBody] = useState('')
  const [posting, setPosting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function post() {
    const text = body.trim()
    if (!text || posting) return
    setError(null)
    setPosting(true)
    try {
      onPosted(await forumApi.answer(courseId, threadId, text))
      setBody('')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not post that reply.')
    } finally {
      setPosting(false)
    }
  }

  return (
    <Panel className="flex flex-col gap-3">
      <TextArea
        value={body}
        rows={4}
        maxLength={8000}
        placeholder="Answer it as you'd explain it to a classmate…"
        disabled={posting}
        onChange={(event) => setBody(event.target.value)}
      />
      <div className="flex items-center justify-between gap-4">
        <Meta>An instructor decides which reply becomes course material.</Meta>
        <Button variant="primary" onClick={() => void post()} disabled={posting || !body.trim()}>
          {posting ? 'Posting…' : 'Reply'}
        </Button>
      </div>
      {error && <ErrorText>{error}</ErrorText>}
    </Panel>
  )
}
