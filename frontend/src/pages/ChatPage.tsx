import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError, chatApi, coursesApi } from '../lib/api'
import type { Citation, CourseResponse } from '../lib/types'
import { AppHeader } from '../components/AppHeader'
import { PdfViewer } from '../components/PdfViewer'

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
    <div className="flex h-screen flex-col bg-slate-50 dark:bg-slate-950">
      <AppHeader />
      <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col overflow-hidden px-6 py-6">
        <Link to={`/courses/${id}`} className="text-sm text-slate-500 hover:underline dark:text-slate-400">
          ← {course?.name ?? 'Course'}
        </Link>
        <h1 className="mt-2 text-xl font-semibold tracking-tight text-slate-900 dark:text-slate-100">
          Ask this course
        </h1>

        <div ref={threadRef} className="mt-4 flex-1 space-y-4 overflow-y-auto pr-1">
          {turns.length === 0 && (
            <p className="mt-10 text-center text-sm text-slate-400">
              Ask a question about this course's materials. Answers cite the sources they came from —
              click a citation to open the exact page.
            </p>
          )}
          {turns.map((turn, i) =>
            turn.role === 'user' ? (
              <UserBubble key={i} text={turn.text} />
            ) : (
              <AssistantBubble key={i} turn={turn} onCite={setActiveCitation} />
            ),
          )}
        </div>

        {error && <p className="mt-2 text-sm text-red-500">{error}</p>}

        <form
          className="mt-3 flex items-end gap-2"
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
            className="max-h-40 flex-1 resize-none rounded-xl border border-slate-300 bg-white px-4 py-2.5 text-sm text-slate-900 outline-none focus:border-indigo-400 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          />
          <button
            type="submit"
            disabled={sending || !input.trim()}
            className="rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-medium text-white transition hover:bg-indigo-500 disabled:opacity-50"
          >
            {sending ? 'Sending…' : 'Send'}
          </button>
        </form>
      </main>

      {activeCitation && (
        <PdfViewer
          key={activeCitation.documentId}
          courseId={id}
          citation={activeCitation}
          onClose={() => setActiveCitation(null)}
        />
      )}
    </div>
  )
}

function UserBubble({ text }: { text: string }) {
  return (
    <div className="flex justify-end">
      <div className="max-w-[80%] whitespace-pre-wrap rounded-2xl rounded-br-sm bg-indigo-600 px-4 py-2.5 text-sm text-white">
        {text}
      </div>
    </div>
  )
}

function AssistantBubble({ turn, onCite }: { turn: Turn; onCite: (c: Citation) => void }) {
  return (
    <div className="flex justify-start">
      <div className="max-w-[85%] rounded-2xl rounded-bl-sm border border-slate-200 bg-white px-4 py-3 text-sm text-slate-800 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-200">
        <AnswerText text={turn.text} citations={turn.citations} onCite={onCite} />
        {turn.streaming && <span className="ml-0.5 inline-block animate-pulse">▋</span>}
        {!turn.streaming && turn.citations.length > 0 && (
          <div className="mt-3 border-t border-slate-100 pt-2 dark:border-slate-800">
            <p className="mb-1 text-xs font-medium text-slate-400">Sources</p>
            <ul className="flex flex-col gap-1">
              {turn.citations.map((c) => (
                <li key={c.index}>
                  <button
                    type="button"
                    onClick={() => onCite(c)}
                    className="text-left text-xs text-slate-500 hover:text-indigo-600 hover:underline dark:text-slate-400 dark:hover:text-indigo-400"
                  >
                    [{c.index}] {c.filename}
                    {c.pageNumber != null && ` · p.${c.pageNumber}`}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
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
                className="mx-0.5 rounded bg-indigo-100 px-1 align-baseline text-xs font-medium text-indigo-700 hover:bg-indigo-200 dark:bg-indigo-950/50 dark:text-indigo-300"
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
