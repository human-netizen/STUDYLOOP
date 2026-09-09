import type { DocumentResponse } from '../lib/types'

// Phase 28.2 — aim the question at one lecture.
//
// Retrieval has been course-wide since Phase 5, which is right for "what does this course say about
// X" and wrong the night before an exam: a student revising one lecture is searching thirteen
// others for no reason, and the fused top-6 can be filled by a chapter they are not reading.
//
// **Nothing selected means the whole course**, which is the default and stays the common case — a
// question about the course is a question about the course. The chips are additive rather than a
// dropdown so the state is visible without opening anything: what is being searched is on screen
// while the question is being typed.
export function DocumentScopePicker({
  documents,
  selected,
  onToggle,
  onClear,
}: {
  documents: DocumentResponse[]
  selected: string[]
  onToggle: (documentId: string) => void
  onClear: () => void
}) {
  // Only documents that can actually answer. A retired one (27.3) is out of every answer by
  // design, and offering it here would be offering a filter that returns nothing.
  const askable = documents.filter((document) => document.status === 'READY')
  if (askable.length < 2) return null

  return (
    <div className="mb-2 flex flex-wrap items-center gap-1.5">
      <span className="text-[12px] text-ink-muted">
        {selected.length === 0
          ? 'Searching the whole course'
          : `Searching ${selected.length} of ${askable.length}`}
      </span>
      {askable.map((document) => {
        const on = selected.includes(document.id)
        return (
          <button
            key={document.id}
            type="button"
            aria-pressed={on}
            onClick={() => onToggle(document.id)}
            className={`max-w-52 cursor-pointer truncate rounded-full border px-2 py-0.5 text-[11.5px] transition duration-150 ${
              on
                ? 'border-accent/60 bg-accent/15 text-ink'
                : 'border-line bg-surface text-ink-muted hover:text-ink'
            }`}
          >
            {document.filename}
          </button>
        )
      })}
      {selected.length > 0 && (
        <button
          type="button"
          onClick={onClear}
          className="cursor-pointer border-0 bg-transparent px-1 text-[11.5px] text-ink-muted underline underline-offset-2 hover:text-ink"
        >
          whole course
        </button>
      )}
    </div>
  )
}
