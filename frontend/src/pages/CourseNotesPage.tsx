import { useCallback, useEffect, useRef, useState, type DragEvent } from 'react'
import { useParams } from 'react-router-dom'
import { ApiError, coursesApi, notesApi } from '../lib/api'
import type { CourseResponse, DocumentStatus, NoteBlock, NoteResponse } from '../lib/types'
import { AppShell } from '../components/AppShell'
import { Markdown } from '../components/Markdown'
import {
  Button,
  Empty,
  ErrorText,
  Loading,
  Meta,
  PageTitle,
  Pill,
  Row,
  Rows,
  SectionHead,
} from '../components/ui'
import { cx } from '../lib/style'

// Phase 16.3 — photograph a page of your notes, have it read, and decide what happens to it.
//
// The screen exists because of the confidence threshold rather than in spite of it. A reader that
// silently dropped the lines it could not make out would leave a gap in your own revision notes
// that you cannot see and would never think to look for; so every block the model read is listed,
// with what it said about itself, and the ones that did not make the cut are marked as not
// answering anything.
const IN_FLIGHT: DocumentStatus[] = ['UPLOADED', 'EXTRACTING', 'CHUNKING', 'EMBEDDING']
const POLL_INTERVAL_MS = 2500

export function CourseNotesPage() {
  const { id = '' } = useParams()

  const [course, setCourse] = useState<CourseResponse | null>(null)
  const [notes, setNotes] = useState<NoteResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(
    () => notesApi.list(id).then(setNotes),
    [id],
  )

  useEffect(() => {
    let active = true
    Promise.all([coursesApi.get(id), notesApi.list(id)])
      .then(([courseData, list]) => {
        if (!active) return
        setCourse(courseData)
        setNotes(list)
      })
      .catch((err) => {
        if (active) setError(err instanceof ApiError ? err.message : 'Failed to load these notes.')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [id])

  // Reading a photograph takes a few seconds and happens on a worker, so the list is re-fetched
  // while anything is still moving — the same poll the documents page runs, for the same reason.
  useEffect(() => {
    if (!notes.some((note) => IN_FLIGHT.includes(note.status))) return
    const timer = setInterval(() => {
      void refresh().catch(() => undefined)
    }, POLL_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [notes, refresh])

  const manager = course?.myRole === 'OWNER' || course?.myRole === 'INSTRUCTOR'

  return (
    <AppShell courseName={course?.name}>
      {loading && <Loading>Loading notes</Loading>}
      {error && <ErrorText>{error}</ErrorText>}

      {!loading && !error && (
        <>
          <PageTitle
            eyebrow={course?.name ?? 'Course'}
            title="Your notes"
            sub="Photograph a page of handwriting and it is read, indexed and answerable — privately, until a manager promotes it to the course."
          />

          <section className="mb-14">
            <SectionHead
              index="01 · Digitise"
              title="Add a page"
              description="A clear, straight photo in good light. The reader marks anything it is unsure of rather than guessing."
            />
            <NoteDropzone courseId={id} onUploaded={() => void refresh()} />
          </section>

          <section>
            <SectionHead
              index="02 · Pages"
              title="Digitised notes"
              description={
                notes.length > 0
                  ? `${notes.filter((note) => note.mine).length} yours · ${
                      notes.filter((note) => !note.mine).length
                    } promoted to the course`
                  : undefined
              }
            />
            {notes.length === 0 ? (
              <Empty>No notes yet — photograph a page above to start.</Empty>
            ) : (
              <Rows>
                {notes.map((note) => (
                  <NoteRow
                    key={note.id}
                    courseId={id}
                    note={note}
                    manager={manager}
                    onChanged={() => void refresh()}
                  />
                ))}
              </Rows>
            )}
          </section>
        </>
      )}
    </AppShell>
  )
}

function NoteDropzone({
  courseId,
  onUploaded,
}: {
  courseId: string
  onUploaded: () => void
}) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleFiles = useCallback(
    async (files: FileList | null) => {
      const file = files?.[0]
      if (!file) return
      setError(null)
      setUploading(true)
      try {
        await notesApi.upload(courseId, file)
        onUploaded()
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Upload failed.')
      } finally {
        setUploading(false)
      }
    },
    [courseId, onUploaded],
  )

  return (
    <div>
      <div
        role="button"
        tabIndex={0}
        onClick={() => !uploading && inputRef.current?.click()}
        onKeyDown={(event) => {
          if ((event.key === 'Enter' || event.key === ' ') && !uploading) {
            event.preventDefault()
            inputRef.current?.click()
          }
        }}
        onDragOver={(event: DragEvent<HTMLDivElement>) => {
          event.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event: DragEvent<HTMLDivElement>) => {
          event.preventDefault()
          setDragging(false)
          if (!uploading) void handleFiles(event.dataTransfer.files)
        }}
        className={cx(
          'flex cursor-pointer flex-col items-center justify-center gap-1 rounded-card border border-dashed px-6 py-12 text-center transition duration-150',
          dragging ? 'border-accent bg-surface-2' : 'border-line bg-surface hover:border-line-strong',
          uploading && 'pointer-events-none opacity-60',
        )}
      >
        <p className="m-0 font-display text-[17px] font-bold tracking-[-0.015em] text-ink">
          {uploading ? 'Reading…' : 'Drop a photo here'}
        </p>
        <Meta>or click to browse · PNG or JPEG</Meta>
      </div>
      <input
        ref={inputRef}
        type="file"
        accept="image/png,image/jpeg,.png,.jpg,.jpeg"
        className="hidden"
        onChange={(event) => {
          void handleFiles(event.target.files)
          event.target.value = ''
        }}
      />
      {error && <ErrorText className="mt-3">{error}</ErrorText>}
    </div>
  )
}

function NoteRow({
  courseId,
  note,
  manager,
  onChanged,
}: {
  courseId: string
  note: NoteResponse
  manager: boolean
  onChanged: () => void
}) {
  const [open, setOpen] = useState(false)
  const [blocks, setBlocks] = useState<NoteBlock[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const fetched = useRef(false)

  // Fetched on first open, so a page listing twenty notes does not fire twenty requests to
  // render a list of filenames.
  useEffect(() => {
    if (!open || fetched.current || note.status !== 'READY') return
    fetched.current = true
    notesApi
      .blocks(courseId, note.id)
      .then(setBlocks)
      .catch((err) =>
        setError(err instanceof ApiError ? err.message : 'Could not load what was read.'),
      )
  }, [open, courseId, note.id, note.status])

  async function act(action: 'promote' | 'demote') {
    setBusy(true)
    setError(null)
    try {
      await (action === 'promote'
        ? notesApi.promote(courseId, note.id)
        : notesApi.demote(courseId, note.id))
      onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : `Could not ${action} this note.`)
    } finally {
      setBusy(false)
    }
  }

  // The browser cannot fetch this with a bearer token from a plain link, so the bytes come back
  // as a Blob and are handed to a temporary object URL — the same trick the PDF viewer uses.
  async function downloadLatex() {
    setBusy(true)
    setError(null)
    try {
      const blob = await notesApi.latex(courseId, note.id)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `${note.filename.replace(/\.[^.]+$/, '')}.tex`
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not export this note.')
    } finally {
      setBusy(false)
    }
  }

  const dropped = blocks?.filter((block) => !block.indexed).length ?? 0

  return (
    <Row interactive>
      <div className="flex flex-wrap items-center gap-3 px-4 py-3">
        <button
          type="button"
          onClick={() => setOpen((was) => !was)}
          className="flex-1 cursor-pointer border-0 bg-transparent p-0 text-left text-sm text-ink"
        >
          {note.filename}
        </button>
        <Pill tone={statusTone(note.status)}>{note.status.toLowerCase()}</Pill>
        {/* Whose it is and who can be answered from it — the two facts a note has that a
            document does not. */}
        <Pill tone={note.visibility === 'COURSE' ? 'accent' : 'neutral'}>
          {note.visibility === 'COURSE' ? 'course' : 'private'}
        </Pill>
        {!note.mine && <Pill tone="neutral">shared</Pill>}
      </div>

      {open && (
        <div className="px-4 pb-4">
          {note.status === 'FAILED' && (
            <ErrorText className="mb-3">{note.errorMessage ?? 'This page could not be read.'}</ErrorText>
          )}
          {note.status !== 'READY' && note.status !== 'FAILED' && <Loading>Reading the page</Loading>}

          {note.status === 'READY' && blocks === null && !error && <Loading>Loading</Loading>}
          {error && <ErrorText className="mb-3">{error}</ErrorText>}

          {blocks && (
            <>
              {dropped > 0 && (
                <Meta className="mb-3 block">
                  {dropped === 1 ? '1 block was' : `${dropped} blocks were`} not confident enough to
                  index. {dropped === 1 ? 'It is' : 'They are'} shown below and will not be used to
                  answer anything.
                </Meta>
              )}
              <div className="flex flex-col gap-3">
                {blocks.map((block) => (
                  <div
                    key={block.ordinal}
                    className={cx(
                      'rounded-card border px-3 py-2',
                      block.indexed ? 'border-line bg-surface' : 'border-warn/40 bg-warn-bg',
                    )}
                  >
                    <div className="mb-1 flex items-center gap-2">
                      <Pill tone={block.indexed ? 'ok' : 'warn'}>
                        {block.indexed ? 'indexed' : 'not indexed'}
                      </Pill>
                      <Meta>{Math.round(block.confidence * 100)}% confident</Meta>
                    </div>
                    <Markdown text={block.content} />
                  </div>
                ))}
              </div>
            </>
          )}

          {note.status === 'READY' && (
            <div className="mt-4 flex flex-wrap gap-2">
              <Button variant="ghost" size="sm" disabled={busy} onClick={() => void downloadLatex()}>
                Export LaTeX
              </Button>
              {/* Manager-gated, because promoting writes member-authored text into the corpus
                  every future answer may be grounded on and cite. */}
              {manager && note.visibility === 'OWNER' && (
                <Button variant="primary" size="sm" disabled={busy} onClick={() => void act('promote')}>
                  Promote to course
                </Button>
              )}
              {manager && note.visibility === 'COURSE' && (
                <Button variant="ghost" size="sm" disabled={busy} onClick={() => void act('demote')}>
                  Take back out of the course
                </Button>
              )}
            </div>
          )}
        </div>
      )}
    </Row>
  )
}

function statusTone(status: DocumentStatus): 'ok' | 'bad' | 'warn' {
  if (status === 'READY') return 'ok'
  if (status === 'FAILED') return 'bad'
  return 'warn'
}
