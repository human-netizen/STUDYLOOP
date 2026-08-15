import { useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import { ApiError, chatApi, coursesApi, flashcardsApi } from '../lib/api'
import type { Citation, CourseResponse } from '../lib/types'
import { AppShell } from '../components/AppShell'
import { PdfViewer } from '../components/PdfViewer'
import { Button, ErrorText, Eyebrow } from '../components/ui'
import { cx } from '../lib/style'

// One rendered turn in the thread. Assistant turns grow token-by-token while `streaming`, and
// carry the citations the answer's [n] markers refer to.
interface Turn {
  role: 'user' | 'assistant'
  text: string
  citations: Citation[]
  streaming: boolean
}

export function ChatPage() {
  const { id = '' } = useParams()

  const [course, setCourse] = useState<CourseResponse | null>(null)
  const [turns, setTurns] = useState<Turn[]>([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [conversationId, setConversationId] = useState<string | null>(null)
  const [activeCitation, setActiveCitation] = useState<Citation | null>(null)

  const threadRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    coursesApi
      .get(id)
      .then(setCourse)
      .catch((err) => setError(err instanceof ApiError ? err.message : 'Failed to load this course.'))
  }, [id])

  // Keep the newest turn in view as the answer streams in.
  useEffect(() => {
    threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight })
  }, [turns])

  // Mutate only the last (assistant) turn — used by the streaming callbacks.
  const updateLast = (patch: (turn: Turn) => Turn) =>
    setTurns((current) => {
      if (current.length === 0) return current
      const next = current.slice()
      next[next.length - 1] = patch(next[next.length - 1])
      return next
    })

  async function submit() {
    const question = input.trim()
    if (!question || sending) return
    setError(null)
    setInput('')
    setSending(true)
    setTurns((current) => [
      ...current,
      { role: 'user', text: question, citations: [], streaming: false },
      { role: 'assistant', text: '', citations: [], streaming: true },
    ])

    try {
      await chatApi.stream(
        id,
        { question, conversationId },
        {
          onMeta: (meta) => {
            setConversationId(meta.conversationId)
            updateLast((turn) => ({ ...turn, citations: meta.citations }))
          },
          onDelta: (text) => updateLast((turn) => ({ ...turn, text: turn.text + text })),
          onDone: () => updateLast((turn) => ({ ...turn, streaming: false })),
          onError: (message) => {
            setError(message)
            updateLast((turn) => ({
              ...turn,
              streaming: false,
              text: turn.text || 'Sorry — something went wrong.',
            }))
          },
        },
      )
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'The assistant is unavailable right now.'
      setError(message)
      updateLast((turn) => ({
        ...turn,
        streaming: false,
        text: turn.text || 'Sorry — something went wrong.',
      }))
    } finally {
      setSending(false)
    }
  }

  return (
    <AppShell courseName={course?.name} fill>
      <div className="mb-5 shrink-0">
        <Eyebrow>{course?.name ?? 'Course'}</Eyebrow>
        <h1 className="mt-1.5 mb-0 text-[clamp(28px,3.4vw,40px)] leading-[1] tracking-[-0.03em]">
          Ask this course
        </h1>
      </div>

      {/* The thread sits in a recessed well so the composer below it reads as a separate
          surface rather than as part of the same scrolling page. */}
      <div
        ref={threadRef}
        className="flex-1 space-y-4 overflow-y-auto rounded-card border border-line bg-ground-2 p-4 sm:p-5"
      >
        {turns.length === 0 && (
          <div className="flex h-full min-h-40 items-center justify-center">
            <p className="m-0 max-w-[46ch] text-center text-sm text-ink-muted">
              Ask about this course's materials. Every answer cites the pages it came from —
              click a citation to open that exact page.
            </p>
          </div>
        )}
        {turns.map((turn, i) =>
          turn.role === 'user' ? (
            <UserBubble key={i} text={turn.text} />
          ) : (
            <AssistantBubble
              key={i}
              turn={turn}
              question={turns[i - 1]?.text ?? ''}
              courseId={id}
              onCite={setActiveCitation}
            />
          ),
        )}
      </div>

      {error && <ErrorText className="mt-2 shrink-0">{error}</ErrorText>}

      {/* One bordered bar, send button inset — not a textarea with a button floating beside it. */}
      <form
        className="mt-3 flex shrink-0 items-end gap-2 rounded-card border border-line bg-surface p-2 transition duration-150 focus-within:border-accent"
        onSubmit={(e) => {
          e.preventDefault()
          void submit()
        }}
      >
        <textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              void submit()
            }
          }}
          rows={1}
          placeholder="Ask a question…"
          className="max-h-40 min-h-9 flex-1 resize-none border-0 bg-transparent px-2 py-1.5 text-sm text-ink outline-none"
        />
        <Button type="submit" variant="primary" size="sm" disabled={sending || !input.trim()}>
          {sending ? 'Sending…' : 'Send'}
        </Button>
      </form>

      {activeCitation && (
        <PdfViewer
          key={activeCitation.documentId}
          courseId={id}
          citation={activeCitation}
          onClose={() => setActiveCitation(null)}
        />
      )}
    </AppShell>
  )
}

// Your own turns carry expert blue — the one place accent-2 is spent. A tint rather than a
// fill, so it separates the two speakers without competing with the cyan on this screen.
function UserBubble({ text }: { text: string }) {
  return (
    <div className="flex justify-end">
      <div className="max-w-[80%] rounded-card rounded-br-[2px] border border-accent-2/35 bg-accent-2/12 px-4 py-2.5 text-sm whitespace-pre-wrap text-ink">
        {text}
      </div>
    </div>
  )
}

function AssistantBubble({
  turn,
  question,
  courseId,
  onCite,
}: {
  turn: Turn
  question: string
  courseId: string
  onCite: (c: Citation) => void
}) {
  const answered = !turn.streaming && turn.text.trim().length > 0
  return (
    <div className="flex justify-start">
      <div className="max-w-[85%] rounded-card rounded-bl-[2px] border border-line bg-surface px-4 py-3 text-sm text-ink-2">
        <AnswerText text={turn.text} citations={turn.citations} onCite={onCite} />
        {turn.streaming && <span className="ml-0.5 inline-block animate-pulse text-accent">▋</span>}
        {!turn.streaming && turn.citations.length > 0 && (
          <div className="mt-3 border-t border-line-soft pt-2">
            <Eyebrow className="mb-1.5">Sources</Eyebrow>
            <ul className="m-0 flex list-none flex-col gap-1 p-0">
              {turn.citations.map((c) => (
                <li key={c.index}>
                  <button
                    type="button"
                    onClick={() => onCite(c)}
                    className="tnum cursor-pointer border-0 bg-transparent p-0 text-left font-mono text-[11.5px] text-ink-muted underline decoration-transparent underline-offset-2 transition duration-150 hover:text-ink hover:decoration-accent"
                  >
                    [{c.index}] {c.filename}
                    {c.pageNumber != null && ` · p.${c.pageNumber}`}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}
        {answered && question && (
          <SaveFlashcardButton courseId={courseId} front={question} back={turn.text} />
        )}
      </div>
    </div>
  )
}

// Turns a completed Q&A into a personal flashcard (question → front, answer → back).
function SaveFlashcardButton({
  courseId,
  front,
  back,
}: {
  courseId: string
  front: string
  back: string
}) {
  const [state, setState] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle')

  async function save() {
    setState('saving')
    try {
      await flashcardsApi.create(courseId, { front, back })
      setState('saved')
    } catch {
      setState('error')
    }
  }

  return (
    <div className="mt-3">
      <Button
        variant="quiet"
        onClick={() => void save()}
        disabled={state === 'saving' || state === 'saved'}
      >
        {state === 'saved'
          ? 'Saved to flashcards ✓'
          : state === 'saving'
            ? 'Saving…'
            : state === 'error'
              ? 'Could not save — retry'
              : '+ Save as flashcard'}
      </Button>
    </div>
  )
}

// Renders answer text with inline [n] markers turned into clickable chips that open the matching
// citation. Markers without a matching citation are left as plain text.
function AnswerText({
  text,
  citations,
  onCite,
}: {
  text: string
  citations: Citation[]
  onCite: (c: Citation) => void
}) {
  const parts = text.split(/(\[\d+\])/g)
  return (
    <span className="whitespace-pre-wrap">
      {parts.map((part, i) => {
        const match = part.match(/^\[(\d+)\]$/)
        if (match) {
          const index = Number(match[1])
          const citation = citations.find((c) => c.index === index)
          if (citation) {
            return (
              <button
                key={i}
                type="button"
                onClick={() => onCite(citation)}
                className={cx(
                  'tnum mx-0.5 cursor-pointer rounded-[3px] border border-accent-deep bg-surface-2 px-1 align-baseline',
                  'font-mono text-[10.5px] text-ink transition duration-150 hover:bg-accent hover:text-on-accent',
                )}
              >
                {part}
              </button>
            )
          }
        }
        return <span key={i}>{part}</span>
      })}
    </span>
  )
}
