import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  ApiError,
  chatApi,
  coursesApi,
  documentsApi,
  errorMessage,
  flashcardsApi,
  forumApi,
} from '../lib/api'
import type {
  AskedBefore,
  Citation,
  ConversationSummary,
  CourseResponse,
  DocumentResponse,
} from '../lib/types'
import { filenameSafe, saveBlob } from '../lib/download'
import { threadToMarkdown } from '../lib/transcript'
import { AnswerFeedback } from '../components/AnswerFeedback'
import { AppShell } from '../components/AppShell'
import { CitationPreview } from '../components/CitationPreview'
import { ConversationSidebar } from '../components/ConversationSidebar'
import { DocumentScopePicker } from '../components/DocumentScopePicker'
import { Markdown } from '../components/Markdown'
import { PdfViewer, pdfTargetOf, type PdfTarget } from '../components/PdfViewer'
import { Button, ErrorText, Eyebrow } from '../components/ui'

// One rendered turn in the thread. Assistant turns grow token-by-token while `streaming`, and
// carry the citations the answer's [n] markers refer to.
//
// `questionEventId` is set only when the assistant refused: it is the handle for escalating that
// exact question — to the forum, or to general knowledge — so a refusal is an offer rather than a
// dead end.
//
// `general` marks the one kind of turn on this screen that did not come from the course's
// materials (Phase 20.2). It is a separate flag rather than an absence of citations because the
// difference has to survive somebody later rendering an answer that simply happens to cite
// nothing: this one is labelled, and the label is the whole reason the feature is allowed to
// exist.
interface Turn {
  role: 'user' | 'assistant'
  text: string
  citations: Citation[]
  streaming: boolean
  // What the server is doing right now (Phase 26.1). Shown in place of the empty bubble while the
  // answer is still being worked out, and replaced by the first token — at which point the text is
  // the progress report and a status line beside it would only compete with it.
  stage: string | null
  questionEventId: string | null
  // Phase 28.3 — the handle a verdict on this answer attaches to. Set on every logged turn, unlike
  // `questionEventId` above, which is the refusal handle and is what `refused` tests.
  answerEventId: string | null
  askedBefore: AskedBefore | null
  general: boolean
}

export function ChatPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()

  const [course, setCourse] = useState<CourseResponse | null>(null)
  const [turns, setTurns] = useState<Turn[]>([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [conversationId, setConversationId] = useState<string | null>(null)
  // A PdfTarget rather than the Citation itself: a citation is not necessarily openable, and
  // pdfTargetOf is what decides (Phase 27.3). Chat's own citations always are — they are
  // grounded on chunks retrieved a moment earlier — so this narrows and never drops one.
  const [activeCitation, setActiveCitation] = useState<PdfTarget | null>(null)

  // Phase 28.1 — the threads this member has had with this course.
  const [conversations, setConversations] = useState<ConversationSummary[]>([])
  const [loadingThreads, setLoadingThreads] = useState(true)
  const [resuming, setResuming] = useState(false)
  // Phase 28.2 — which documents the next question may be answered from. Empty is the whole
  // course, which is the default and stays the common case.
  const [documents, setDocuments] = useState<DocumentResponse[]>([])
  const [scope, setScope] = useState<string[]>([])

  const threadRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    coursesApi
      .get(id)
      .then(setCourse)
      .catch((err) => setError(err instanceof ApiError ? err.message : 'Failed to load this course.'))
  }, [id])

  // The document list feeds the scope chips. A failure here is not worth an error banner: the
  // picker simply does not appear and every question searches the whole course, which is what it
  // did before this phase.
  useEffect(() => {
    documentsApi
      .list(id)
      .then(setDocuments)
      .catch(() => setDocuments([]))
  }, [id])

  const loadConversations = useCallback(async () => {
    setLoadingThreads(true)
    try {
      setConversations(await chatApi.conversations(id))
    } catch {
      setConversations([])
    } finally {
      setLoadingThreads(false)
    }
  }, [id])

  useEffect(() => {
    void loadConversations()
  }, [loadConversations])

  // Reopen a thread: the transcript replaces what is on screen and the id goes back into state, so
  // the next question continues it rather than opening a new one.
  //
  // **A resumed ASSISTANT turn carries its stored citations** (V29), which is what makes this worth
  // doing at all — the alternative is a transcript whose [n] markers are dead text. A turn stored
  // before that column existed comes back with an empty list and renders its markers as plain text,
  // which is the honest reading of a turn whose sources were never kept.
  async function openThread(threadId: string) {
    setResuming(true)
    setError(null)
    try {
      const transcript = await chatApi.transcript(id, threadId)
      setConversationId(transcript.id)
      setTurns(
        transcript.messages.map((message) => ({
          ...blank,
          role: message.role === 'USER' ? 'user' : 'assistant',
          text: message.content,
          citations: message.citations,
          general: message.role === 'GENERAL',
        })),
      )
    } catch (err) {
      setError(errorMessage(err, 'Could not open that thread.'))
    } finally {
      setResuming(false)
    }
  }

  function newThread() {
    setConversationId(null)
    setTurns([])
    setError(null)
  }

  async function deleteThread(threadId: string) {
    try {
      await chatApi.removeConversation(id, threadId)
      if (threadId === conversationId) newThread()
      await loadConversations()
    } catch (err) {
      setError(errorMessage(err, 'Could not delete that thread.'))
    }
  }

  // Phase 29.1's last bullet, which needed this endpoint to exist. The file is built from the
  // stored transcript rather than from what is on screen, so an exported thread is the whole thread
  // even when the page was reopened halfway down it.
  async function exportThread() {
    if (!conversationId) return
    try {
      const transcript = await chatApi.transcript(id, conversationId)
      const name = filenameSafe(transcript.title ?? 'conversation', 'conversation')
      saveBlob(
        new Blob([threadToMarkdown(transcript)], { type: 'text/markdown;charset=utf-8' }),
        `${name}.md`,
      )
    } catch (err) {
      setError(errorMessage(err, 'Could not export this thread.'))
    }
  }

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
      { ...blank, role: 'user', text: question },
      { ...blank, role: 'assistant', streaming: true },
    ])

    try {
      await chatApi.stream(
        id,
        // An empty scope is sent as an empty list, which the server reads as the whole course —
        // the same request every caller made before Phase 28.2.
        { question, conversationId, documentIds: scope },
        {
          onStage: (stage) => updateLast((turn) => ({ ...turn, stage })),
          onMeta: (meta) => {
            setConversationId(meta.conversationId)
            updateLast((turn) => ({
              ...turn,
              citations: meta.citations,
              questionEventId: meta.questionEventId,
              answerEventId: meta.answerEventId,
              askedBefore: meta.askedBefore,
            }))
          },
          onDelta: (text) => updateLast((turn) => ({ ...turn, text: turn.text + text, stage: null })),
          onDone: () => updateLast((turn) => ({ ...turn, streaming: false, stage: null })),
          onError: (message) => {
            setError(message)
            updateLast((turn) => ({
              ...turn,
              streaming: false,
              stage: null,
              text: turn.text || 'Sorry — something went wrong.',
            }))
          },
        },
      )
    } catch (err) {
      // Covers the quota refusals too: a 429 arrives here with the wait already folded in, so
      // "you've used this period's allowance" doesn't read as "the assistant is broken".
      const message = errorMessage(err, 'The assistant is unavailable right now.')
      setError(message)
      updateLast((turn) => ({
        ...turn,
        streaming: false,
        stage: null,
        text: turn.text || 'Sorry — something went wrong.',
      }))
    } finally {
      setSending(false)
      // The thread list carries a turn count and an updated-at, and this turn changed both.
      void loadConversations()
    }
  }

  return (
    <AppShell courseName={course?.name} fill>
      <div className="mb-5 flex shrink-0 flex-wrap items-end justify-between gap-3">
        <div>
          <Eyebrow>{course?.name ?? 'Course'}</Eyebrow>
          <h1 className="mt-1.5 mb-0 text-[clamp(28px,3.4vw,40px)] leading-[1] tracking-[-0.03em]">
            Ask this course
          </h1>
        </div>
        {/* Phase 29.1's chat export, which needed 28.1's transcript endpoint before it could
            exist. Only for a thread that has been saved — there is nothing to export from a
            composer somebody has not sent yet. */}
        {conversationId && (
          <Button variant="quiet" onClick={() => void exportThread()}>
            Export as Markdown
          </Button>
        )}
      </div>

      <div className="flex min-h-0 flex-1 flex-col gap-4 lg:flex-row">
        <ConversationSidebar
          conversations={conversations}
          activeId={conversationId}
          loading={loadingThreads}
          onOpen={(threadId) => void openThread(threadId)}
          onNew={newThread}
          onDelete={(threadId) => void deleteThread(threadId)}
        />

        <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      {/* The thread sits in a recessed well so the composer below it reads as a separate
          surface rather than as part of the same scrolling page. */}
      <div
        ref={threadRef}
        className="flex-1 space-y-4 overflow-y-auto rounded-card border border-line bg-ground-2 p-4 sm:p-5"
      >
        {resuming && <p className="m-0 text-[13px] text-ink-muted">Opening that thread…</p>}
        {turns.length === 0 && !resuming && (
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
              conversationId={conversationId}
              onCite={(cite) => setActiveCitation(pdfTargetOf(cite))}
              onEscalated={(threadId) => navigate(`/courses/${id}/forum/${threadId}`)}
              onGeneralAnswer={(answer) =>
                setTurns((current) => [
                  ...current,
                  { ...blank, role: 'assistant', text: answer, general: true },
                ])
              }
            />
          ),
        )}
      </div>

      {error && <ErrorText className="mt-2 shrink-0">{error}</ErrorText>}

      {/* Phase 28.2 — above the composer, so what is being searched is on screen while the
          question is being typed rather than hidden behind a menu. */}
      <div className="mt-3 shrink-0">
        <DocumentScopePicker
          documents={documents}
          selected={scope}
          onToggle={(documentId) =>
            setScope((current) =>
              current.includes(documentId)
                ? current.filter((each) => each !== documentId)
                : [...current, documentId],
            )
          }
          onClear={() => setScope([])}
        />
      </div>

      {/* One bordered bar, send button inset — not a textarea with a button floating beside it. */}
      <form
        className="flex shrink-0 items-end gap-2 rounded-card border border-line bg-surface p-2 transition duration-150 focus-within:border-accent"
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
        </div>
      </div>

      {activeCitation && (
        <PdfViewer
          key={activeCitation.documentId}
          courseId={id}
          target={activeCitation}
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

// The default shape of a turn, so adding a field to Turn does not mean editing four object
// literals that each set a different subset of it.
const blank: Turn = {
  role: 'assistant',
  text: '',
  citations: [],
  streaming: false,
  stage: null,
  questionEventId: null,
  answerEventId: null,
  askedBefore: null,
  general: false,
}

function AssistantBubble({
  turn,
  question,
  courseId,
  conversationId,
  onCite,
  onEscalated,
  onGeneralAnswer,
}: {
  turn: Turn
  question: string
  courseId: string
  conversationId: string | null
  onCite: (c: Citation) => void
  onEscalated: (threadId: string) => void
  onGeneralAnswer: (answer: string) => void
}) {
  const answered = !turn.streaming && turn.text.trim().length > 0
  const refused = !turn.streaming && turn.questionEventId != null

  // An answer from outside the materials is a different kind of thing and says so — a dashed
  // border, a warning tint and a label, none of which the grounded bubble has.
  if (turn.general) {
    return (
      <div className="flex justify-start">
        <div className="max-w-[85%] rounded-card rounded-bl-[2px] border border-dashed border-warn/40 bg-warn-bg px-4 py-3 text-sm text-ink-2">
          <Eyebrow className="mb-1.5 text-warn">Not from this course</Eyebrow>
          <Markdown text={turn.text} citations={[]} onCite={onCite} streaming={false} />
          <p className="mt-3 mb-0 border-t border-line-soft pt-2 text-[12px] text-ink-muted">
            Answered from general knowledge. Nothing in this course's materials backs it, so there
            are no sources to check — confirm it with your instructor before relying on it.
          </p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex justify-start">
      <div className="max-w-[85%] rounded-card rounded-bl-[2px] border border-line bg-surface px-4 py-3 text-sm text-ink-2">
        {turn.askedBefore && <AskedBeforeNote askedBefore={turn.askedBefore} />}
        {/* The wait, narrated (26.1). A sentence and no bar: a chat turn has no denominator, and a
            progress bar moving at an unpredictable rate from an unknown total invites the reader
            to work out an arrival time from a number nobody measured. It occupies the bubble only
            while it is the only thing there is to say. */}
        {turn.streaming && turn.text.length === 0 && turn.stage && (
          <p className="m-0 flex items-center gap-2 text-[13px] text-ink-muted">
            <span className="inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-ink-muted" />
            {turn.stage}
          </p>
        )}
        <Markdown
          text={turn.text}
          citations={turn.citations}
          onCite={onCite}
          streaming={turn.streaming}
        />
        {!turn.streaming && turn.citations.length > 0 && (
          <div className="mt-3 border-t border-line-soft pt-2">
            <Eyebrow className="mb-1.5">Sources</Eyebrow>
            <ul className="m-0 flex list-none flex-col gap-1 p-0">
              {turn.citations.map((c) => (
                <li key={c.index}>
                  <SourceLink citation={c} onCite={onCite} />
                </li>
              ))}
            </ul>
          </div>
        )}
        {/* A refusal is the one answer with something better to offer than itself — two things,
            in fact, and they are different offers: one asks people, the other asks the model to
            step outside the course and says so. */}
        {refused && (
          <RefusalActions
            courseId={courseId}
            question={question}
            questionEventId={turn.questionEventId!}
            conversationId={conversationId}
            onEscalated={onEscalated}
            onGeneralAnswer={onGeneralAnswer}
          />
        )}
        {answered && question && !refused && (
          <SaveFlashcardButton courseId={courseId} front={question} back={turn.text} />
        )}
        {/* Phase 28.3. Under a grounded answer only: a refusal already offers two better things
            to do than rate it, and "was this right?" under "I don't have that" is a question
            about the gate rather than about the answer. */}
        {answered && !refused && (
          <AnswerFeedback
            courseId={courseId}
            answerEventId={turn.answerEventId}
            question={question}
            citations={turn.citations}
          />
        )}
      </div>
    </div>
  )
}

// A citation is a link to a page in a PDF — unless it came from the forum, which has no file
// behind it. Those render as plain text saying what they are rather than as a click that
// would open an empty viewer.
//
// A `figure` marker is the one other distinction worth making. That citation was found by matching
// a picture rather than words, so the page will not contain the phrase the student searched for,
// and a link that looked like every other one would read as a bad citation rather than as a
// pointer at a diagram.
function SourceLink({ citation, onCite }: { citation: Citation; onCite: (c: Citation) => void }) {
  if (citation.documentSource === 'FORUM') {
    return (
      // A forum source has no page to open, which makes the preview the only way to read it at
      // all — the one place where hovering is not a shortcut but the whole affordance.
      <CitationPreview citation={citation}>
        <span className="tnum block font-mono text-[11.5px] text-ink-muted">
          [{citation.index}] {citation.filename} · answered by the class
        </span>
      </CitationPreview>
    )
  }
  return (
    <CitationPreview citation={citation}>
      <button
        type="button"
        onClick={() => onCite(citation)}
        className="tnum cursor-pointer border-0 bg-transparent p-0 text-left font-mono text-[11.5px] text-ink-muted underline decoration-transparent underline-offset-2 transition duration-150 hover:text-ink hover:decoration-accent"
      >
        [{citation.index}] {citation.filename}
        {citation.pageNumber != null && ` · p.${citation.pageNumber}`}
        {citation.visual && ' · figure'}
      </button>
    </CitationPreview>
  )
}

// The two ways out of a refusal.
//
// "Ask the class" is the one that improves the course: an accepted answer becomes material, so the
// next student gets it from chat. Opening the same refusal twice returns the thread that already
// exists, so a double click cannot split one question into two.
//
// "Answer from general knowledge" is the escape hatch, and it is second, quieter and explicit for
// a reason — it helps this student and teaches the course nothing. It is also counted: the
// instructor's page reports how many refusals were worth a second click, which is a better signal
// about missing material than the refusal count on its own.
function RefusalActions({
  courseId,
  question,
  questionEventId,
  conversationId,
  onEscalated,
  onGeneralAnswer,
}: {
  courseId: string
  question: string
  questionEventId: string
  conversationId: string | null
  onEscalated: (threadId: string) => void
  onGeneralAnswer: (answer: string) => void
}) {
  const [state, setState] = useState<'idle' | 'posting' | 'asking' | 'answered' | 'error'>('idle')

  async function escalate() {
    setState('posting')
    try {
      const thread = await forumApi.open(courseId, { title: question, questionEventId })
      onEscalated(thread.id)
    } catch {
      setState('error')
    }
  }

  async function askGenerally() {
    if (!conversationId) return
    setState('asking')
    try {
      const answer = await chatApi.general(courseId, { question, conversationId, questionEventId })
      onGeneralAnswer(answer.answer)
      setState('answered')
    } catch {
      setState('error')
    }
  }

  const busy = state === 'posting' || state === 'asking'
  return (
    <div className="mt-3 border-t border-line-soft pt-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="primary" size="sm" onClick={() => void escalate()} disabled={busy}>
          {state === 'posting' ? 'Opening…' : 'Ask the class'}
        </Button>
        <Button
          variant="quiet"
          onClick={() => void askGenerally()}
          disabled={busy || state === 'answered' || !conversationId}
        >
          {state === 'asking'
            ? 'Answering…'
            : state === 'answered'
              ? 'Answered below ↓'
              : 'Answer from general knowledge'}
        </Button>
      </div>
      <p className="mt-2 mb-0 text-[12px] text-ink-muted">
        {state === 'error'
          ? 'That did not work — try again.'
          : 'Asking the class puts it in the course forum, where an accepted answer becomes course material. General knowledge answers you now, cites nothing, and changes nothing for anyone else.'}
      </p>
    </div>
  )
}

// The recurring-question header (Phase 20.3). One line, above the answer, and it says only what
// the record supports: how many times this student asked something that meant the same thing, and
// when. It does not repeat what they were told, because that is the part most likely to be the
// reason they are asking again.
function AskedBeforeNote({ askedBefore }: { askedBefore: AskedBefore }) {
  return (
    <div className="mb-2.5 border-b border-line-soft pb-2 text-[12px] text-ink-muted">
      You've asked about this {askedBefore.times === 1 ? 'once' : `${askedBefore.times} times`}{' '}
      before — last on {new Date(askedBefore.lastAskedAt).toLocaleDateString()} (“
      {askedBefore.lastQuestion}”). Here it is explained another way.
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

