import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { ApiError, coursesApi, documentsApi, flashcardsApi } from '../lib/api'
import type { CourseResponse, DocumentResponse, Flashcard } from '../lib/types'
import { AppShell } from '../components/AppShell'
import {
  Button,
  Empty,
  ErrorText,
  Eyebrow,
  Field,
  Input,
  Loading,
  Meta,
  NumberField,
  PageTitle,
  Panel,
  SectionHead,
  Select,
} from '../components/ui'
import { cx } from '../lib/style'

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
    <AppShell courseName={course?.name}>
      <PageTitle
        eyebrow={course?.name ?? 'Course'}
        title="Flashcards"
        sub="Private to you. Click a card to flip it."
      />

      {loading && <Loading />}
      {error && <ErrorText>{error}</ErrorText>}

      {!loading && (
        <>
          <section className="mb-14">
            <SectionHead index="01 · New" title="Build your deck" />
            <div className="grid gap-4 lg:grid-cols-2">
              <GenerateDeck courseId={id} documents={documents} onGenerated={prepend} />
              <AddCard courseId={id} onAdded={(card) => prepend([card])} />
            </div>
          </section>

          <section>
            <SectionHead
              index="02 · Deck"
              title="Your cards"
              description={cards.length > 0 ? `${cards.length} saved` : undefined}
            />
            {cards.length === 0 ? (
              <Empty>No cards yet — generate a deck, add one by hand, or save an answer from chat.</Empty>
            ) : (
              <div className="grid gap-4 sm:grid-cols-2">
                {cards.map((card) => (
                  <FlashcardTile key={card.id} card={card} onDelete={() => void remove(card.id)} />
                ))}
              </div>
            )}
          </section>
        </>
      )}
    </AppShell>
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
    <Panel className="flex flex-col gap-4">
      <div>
        <Eyebrow>From a document</Eyebrow>
        <h3 className="mt-1 mb-0 text-[17px] tracking-[-0.015em]">Generate a deck</h3>
      </div>

      {documents.length === 0 ? (
        <Meta>No ready documents yet — upload and ingest a PDF first.</Meta>
      ) : (
        <>
          <Field label="Document">
            <Select
              value={documentId}
              disabled={generating}
              className="font-mono text-[12.5px]"
              onChange={(event) => setDocumentId(event.target.value)}
            >
              {documents.map((doc) => (
                <option key={doc.id} value={doc.id}>
                  {doc.filename}
                </option>
              ))}
            </Select>
          </Field>
          <div className="flex items-end gap-3">
            <NumberField
              label="Cards"
              value={count}
              min={1}
              max={30}
              disabled={generating}
              onChange={setCount}
            />
            <Button variant="primary" onClick={() => void generate()} disabled={generating}>
              {generating ? 'Generating…' : 'Generate'}
            </Button>
          </div>
          {error && <ErrorText>{error}</ErrorText>}
        </>
      )}
    </Panel>
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
    <Panel className="flex flex-col gap-4">
      <div>
        <Eyebrow>By hand</Eyebrow>
        <h3 className="mt-1 mb-0 text-[17px] tracking-[-0.015em]">Add a card</h3>
      </div>
      <Field label="Front">
        <Input
          value={front}
          disabled={saving}
          onChange={(event) => setFront(event.target.value)}
          placeholder="Question or term"
        />
      </Field>
      <Field label="Back">
        <Input
          value={back}
          disabled={saving}
          onChange={(event) => setBack(event.target.value)}
          placeholder="Answer or definition"
        />
      </Field>
      <Button
        variant="primary"
        onClick={() => void add()}
        disabled={saving || !front.trim() || !back.trim()}
        className="self-start"
      >
        {saving ? 'Saving…' : 'Add card'}
      </Button>
      {error && <ErrorText>{error}</ErrorText>}
    </Panel>
  )
}

// A real 3D flip. The delete control sits outside the rotating element — otherwise it would
// turn with the card and end up mirrored on the back.
function FlashcardTile({ card, onDelete }: { card: Flashcard; onDelete: () => void }) {
  const [flipped, setFlipped] = useState(false)

  const face =
    'flip-face absolute inset-0 flex flex-col gap-2 rounded-card border border-line p-5 text-left'

  return (
    <div className="flip-scene group relative h-44">
      <button
        type="button"
        aria-label={flipped ? 'Show front' : 'Show back'}
        onClick={() => setFlipped((value) => !value)}
        className={cx('flip-card h-full w-full cursor-pointer border-0 bg-transparent p-0', flipped && 'is-flipped')}
      >
        <span className={cx(face, 'bg-surface shadow-card')}>
          <Eyebrow>Front</Eyebrow>
          <span className="overflow-y-auto text-sm text-ink">{card.front}</span>
        </span>
        <span className={cx(face, 'flip-face-back bg-surface-2 shadow-card')}>
          <Eyebrow>Back</Eyebrow>
          <span className="overflow-y-auto text-sm text-ink">{card.back}</span>
        </span>
      </button>
      <button
        type="button"
        onClick={onDelete}
        aria-label="Delete card"
        className="absolute top-2 right-2 z-10 cursor-pointer rounded-ctl border-0 bg-transparent px-2 py-1 font-mono text-[10.5px] tracking-[0.06em] text-ink-muted uppercase opacity-0 transition duration-150 group-focus-within:opacity-100 group-hover:opacity-100 hover:text-bad"
      >
        Delete
      </button>
    </div>
  )
}
