import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError, coursesApi, documentsApi, flashcardsApi } from '../lib/api'
import type { CourseResponse, DocumentResponse, Flashcard } from '../lib/types'
import { AppHeader } from '../components/AppHeader'

// A member's personal flashcards for a course: generate a deck from one ready document, add a
// card by hand, flip cards to study, and delete. Cards are private to the signed-in user.
export function FlashcardsPage() {
  const { id = '' } = useParams()

  const [course, setCourse] = useState<CourseResponse | null>(null)
  const [documents, setDocuments] = useState<DocumentResponse[]>([])
  const [cards, setCards] = useState<Flashcard[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    Promise.all([coursesApi.get(id), documentsApi.list(id), flashcardsApi.list(id)])
      .then(([courseData, docs, cardList]) => {
        if (!active) return
        setCourse(courseData)
        setDocuments(docs.filter((doc) => doc.status === 'READY'))
        setCards(cardList)
      })
      .catch((err) => {
        if (active) setError(err instanceof ApiError ? err.message : 'Failed to load flashcards.')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [id])

  function prepend(newCards: Flashcard[]) {
    setCards((current) => [...newCards, ...current])
  }

  async function remove(cardId: string) {
    // Optimistic: drop it immediately, restore on failure.
    const previous = cards
    setCards((current) => current.filter((card) => card.id !== cardId))
    try {
      await flashcardsApi.remove(id, cardId)
    } catch {
      setCards(previous)
    }
  }

  return (
    <div className="min-h-screen bg-slate-50 dark:bg-slate-950">
      <AppHeader />
      <main className="mx-auto max-w-4xl px-6 py-10">
        <Link to={`/courses/${id}`} className="text-sm text-slate-500 hover:underline dark:text-slate-400">
          ← {course ? course.name : 'Course'}
        </Link>

        <h1 className="mt-4 text-2xl font-semibold tracking-tight text-slate-900 dark:text-slate-100">
          Flashcards
        </h1>

        {loading && <p className="mt-6 text-slate-500 dark:text-slate-400">Loading…</p>}
        {error && <p className="mt-6 text-sm text-red-500">{error}</p>}

        {!loading && (
          <>
            <div className="mt-8 grid gap-4 sm:grid-cols-2">
              <GenerateDeck courseId={id} documents={documents} onGenerated={prepend} />
              <AddCard courseId={id} onAdded={(card) => prepend([card])} />
            </div>

            <h2 className="mt-10 mb-4 text-lg font-semibold tracking-tight text-slate-900 dark:text-slate-100">
              Your cards ({cards.length})
            </h2>
            {cards.length === 0 ? (
              <p className="text-slate-500 dark:text-slate-400">
                No cards yet — generate a deck or add one above. Click a card to flip it.
              </p>
            ) : (
              <div className="grid gap-4 sm:grid-cols-2">
                {cards.map((card) => (
                  <FlashcardCard key={card.id} card={card} onDelete={() => void remove(card.id)} />
                ))}
              </div>
            )}
          </>
        )}
      </main>
    </div>
  )
}

function GenerateDeck({
  courseId,
  documents,
  onGenerated,
}: {
  courseId: string
  documents: DocumentResponse[]
  onGenerated: (cards: Flashcard[]) => void
}) {
  const [documentId, setDocumentId] = useState('')
  const [count, setCount] = useState(10)
  const [generating, setGenerating] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Default the picker to the first ready document once loaded.
  useEffect(() => {
    if (!documentId && documents.length > 0) setDocumentId(documents[0].id)
  }, [documents, documentId])

  async function generate() {
    if (!documentId) return
    setError(null)
    setGenerating(true)
    try {
      const cards = await flashcardsApi.generate(courseId, { documentId, count })
      onGenerated(cards)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not generate cards.')
    } finally {
      setGenerating(false)
    }
  }

  return (
    <div className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
      <p className="font-medium text-slate-800 dark:text-slate-100">Generate from a document</p>
      {documents.length === 0 ? (
        <p className="mt-2 text-xs text-slate-400">
          No ready documents yet — upload and ingest a PDF first.
        </p>
      ) : (
        <>
          <div className="mt-4 flex flex-col gap-3">
            <select
              value={documentId}
              disabled={generating}
              onChange={(event) => setDocumentId(event.target.value)}
              className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100"
            >
              {documents.map((doc) => (
                <option key={doc.id} value={doc.id}>
                  {doc.filename}
                </option>
              ))}
            </select>
            <div className="flex items-end gap-3">
              <label className="flex flex-col gap-1 text-xs font-medium text-slate-500 dark:text-slate-400">
                Cards
                <input
                  type="number"
                  min={1}
                  max={30}
                  value={count}
                  disabled={generating}
                  onChange={(event) => {
                    const next = Number(event.target.value)
                    setCount(Number.isFinite(next) ? Math.min(30, Math.max(1, next)) : 1)
                  }}
                  className="w-24 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-900 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100"
                />
              </label>
              <button
                type="button"
                onClick={() => void generate()}
                disabled={generating}
                className="rounded-xl bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-indigo-500 disabled:opacity-60"
              >
                {generating ? 'Generating…' : 'Generate'}
              </button>
            </div>
          </div>
          {error && <p className="mt-3 text-sm text-red-500">{error}</p>}
        </>
      )}
    </div>
  )
}

function AddCard({ courseId, onAdded }: { courseId: string; onAdded: (card: Flashcard) => void }) {
  const [front, setFront] = useState('')
  const [back, setBack] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function add() {
    if (!front.trim() || !back.trim()) return
    setError(null)
    setSaving(true)
    try {
      const card = await flashcardsApi.create(courseId, { front, back })
      onAdded(card)
      setFront('')
      setBack('')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save the card.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
      <p className="font-medium text-slate-800 dark:text-slate-100">Add a card</p>
      <div className="mt-4 flex flex-col gap-3">
        <input
          value={front}
          disabled={saving}
          onChange={(event) => setFront(event.target.value)}
          placeholder="Front (question or term)"
          className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100"
        />
        <input
          value={back}
          disabled={saving}
          onChange={(event) => setBack(event.target.value)}
          placeholder="Back (answer or definition)"
          className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100"
        />
        <button
          type="button"
          onClick={() => void add()}
          disabled={saving || !front.trim() || !back.trim()}
          className="self-start rounded-xl bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-indigo-500 disabled:opacity-60"
        >
          {saving ? 'Saving…' : 'Add card'}
        </button>
      </div>
      {error && <p className="mt-3 text-sm text-red-500">{error}</p>}
    </div>
  )
}

function FlashcardCard({ card, onDelete }: { card: Flashcard; onDelete: () => void }) {
  const [flipped, setFlipped] = useState(false)
  return (
    <div className="group relative rounded-xl border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
      <button
        type="button"
        onClick={() => setFlipped((value) => !value)}
        className="flex min-h-28 w-full flex-col justify-center gap-1 px-5 py-6 text-left"
      >
        <span className="text-[11px] font-medium uppercase tracking-wide text-slate-400">
          {flipped ? 'Back' : 'Front'}
        </span>
        <span className="text-sm text-slate-800 dark:text-slate-100">
          {flipped ? card.back : card.front}
        </span>
      </button>
      <button
        type="button"
        onClick={onDelete}
        aria-label="Delete card"
        className="absolute right-2 top-2 rounded-md px-2 py-1 text-xs text-slate-400 opacity-0 transition hover:text-red-500 group-hover:opacity-100"
      >
        Delete
      </button>
    </div>
  )
}
