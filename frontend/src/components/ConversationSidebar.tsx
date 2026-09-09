import type { ConversationSummary } from '../lib/types'
import { Button, Confirm, Eyebrow } from './ui'

// Phase 28.1 — the threads you have had with this course.
//
// **The list is what makes the thread id survive a reload**, and until this phase there was none:
// `conversationId` lived in React state, so refreshing the page silently opened a new thread while
// the model was still being replayed the old one on any turn that did survive. Nothing about the
// server changed to make this work — the rows were always there.
//
// Titles are the first question, truncated on the server. A generated title would be a model call
// on the first turn of every conversation forever, paid to summarise a sentence the reader wrote
// and can recognise on sight.
export function ConversationSidebar({
  conversations,
  activeId,
  loading,
  onOpen,
  onNew,
  onDelete,
}: {
  conversations: ConversationSummary[]
  activeId: string | null
  loading: boolean
  onOpen: (id: string) => void
  onNew: () => void
  onDelete: (id: string) => void
}) {
  return (
    <aside className="flex w-full shrink-0 flex-col gap-2 lg:w-60">
      <div className="flex items-center justify-between gap-2">
        <Eyebrow>Your threads</Eyebrow>
        <Button variant="quiet" onClick={onNew}>
          + New
        </Button>
      </div>

      {/* Scrolls on its own so a long history cannot push the composer off the screen. */}
      <div className="flex max-h-44 flex-col gap-1 overflow-y-auto lg:max-h-none lg:flex-1">
        {loading && <p className="m-0 text-[12px] text-ink-muted">Loading…</p>}
        {!loading && conversations.length === 0 && (
          <p className="m-0 text-[12px] text-ink-muted">
            Nothing yet. Ask a question and it will be saved here.
          </p>
        )}
        {conversations.map((conversation) => (
          <div
            key={conversation.id}
            className={`flex flex-col gap-1 rounded-card border px-2 py-1.5 transition duration-150 ${
              conversation.id === activeId
                ? 'border-accent/50 bg-accent/10'
                : 'border-line bg-surface'
            }`}
          >
            <button
              type="button"
              onClick={() => onOpen(conversation.id)}
              className="min-w-0 cursor-pointer border-0 bg-transparent p-0 text-left"
            >
              <span className="block truncate text-[12.5px] text-ink">
                {conversation.title?.trim() || 'Untitled thread'}
              </span>
              <span className="tnum block text-[11px] text-ink-muted">
                {conversation.messageCount} {conversation.messageCount === 1 ? 'turn' : 'turns'} ·{' '}
                {new Date(conversation.updatedAt).toLocaleDateString()}
              </span>
            </button>
            {/* Phase 27.4's delete, which waited for this list to be reached from — a list that can
                only grow is not a feature. The detail is passed rather than fetched: unlike a
                document, a thread's blast radius is known from the row already on screen. */}
            {conversation.id === activeId && (
              <Confirm
                label="Delete"
                question="Delete this thread?"
                detail={`${conversation.messageCount} ${
                  conversation.messageCount === 1 ? 'turn' : 'turns'
                } gone for good. Your flashcards and the course's materials are untouched.`}
                confirmLabel="Delete thread"
                onConfirm={() => onDelete(conversation.id)}
              />
            )}
          </div>
        ))}
      </div>
    </aside>
  )
}
