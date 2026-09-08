import { useCallback, useEffect, useRef, useState, type DragEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError, coursesApi, documentsApi } from '../lib/api'
import type {
  CourseResponse,
  DocumentResponse,
  DocumentStatus,
  DocumentSummary,
} from '../lib/types'
import { AppShell } from '../components/AppShell'
import { Markdown } from '../components/Markdown'
import {
  Button,
  Empty,
  ErrorText,
  Eyebrow,
  Loading,
  Meta,
  NumberField,
  PageTitle,
  Pill,
  ProgressBar,
  Row,
  Rows,
  SectionHead,
} from '../components/ui'
import { estimateFor, pageCountOf } from '../lib/pdf'
import { cx, linkButton } from '../lib/style'

// Statuses that are still moving through the pipeline — while any document sits in one of
// these, we re-poll the list so the UI tracks it to READY/FAILED.
const IN_FLIGHT: DocumentStatus[] = ['UPLOADED', 'EXTRACTING', 'CHUNKING', 'EMBEDDING']
const POLL_INTERVAL_MS = 2500

export function CourseDetailPage() {
  const { id = '' } = useParams()

  const [course, setCourse] = useState<CourseResponse | null>(null)
  const [documents, setDocuments] = useState<DocumentResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    Promise.all([coursesApi.get(id), documentsApi.list(id)])
      .then(([courseData, docs]) => {
        if (!active) return
        setCourse(courseData)
        setDocuments(docs)
      })
      .catch((err) => {
        if (active) setError(err instanceof ApiError ? err.message : 'Failed to load this course.')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [id])

  // Poll while any document is still ingesting. Re-listing (rather than fetching each doc)
  // keeps this to one request per tick and naturally picks up documents others uploaded.
  const anyInFlight = documents.some((doc) => IN_FLIGHT.includes(doc.status))
  useEffect(() => {
    if (!anyInFlight) return
    const timer = setInterval(() => {
      documentsApi
        .list(id)
        .then(setDocuments)
        .catch(() => {
          // Transient poll failure — keep the last known state and try again next tick.
        })
    }, POLL_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [anyInFlight, id])

  // Merge an uploaded/updated document into the list: replace an existing row (re-upload of
  // an identical file returns the same id) or prepend a new one.
  const mergeDocument = useCallback((doc: DocumentResponse) => {
    setDocuments((current) => {
      const index = current.findIndex((existing) => existing.id === doc.id)
      if (index === -1) return [doc, ...current]
      const next = current.slice()
      next[index] = doc
      return next
    })
  }, [])

  const readyCount = documents.filter((doc) => doc.status === 'READY').length

  return (
    <AppShell courseName={course?.name}>
      {loading && <Loading />}
      {error && <ErrorText>{error}</ErrorText>}

      {course && (
        <>
          <PageTitle
            eyebrow={course.myRole}
            title={course.name}
            sub={course.description}
            action={
              <Link to={`/courses/${id}/chat`} className={cx(linkButton('primary'), 'no-underline')}>
                Ask this course
              </Link>
            }
          />

          <section className="mb-14">
            <SectionHead
              index="01 · Materials"
              title="Add material"
              description="PDFs, PowerPoint decks and Word documents are extracted, chunked and embedded before they can be asked about."
            />
            <UploadDropzone courseId={id} onUploaded={mergeDocument} />
          </section>

          <section>
            <SectionHead
              index="02 · Library"
              title="Documents"
              description={
                documents.length > 0
                  ? `${readyCount} of ${documents.length} ready to answer questions`
                  : undefined
              }
            />
            {documents.length === 0 ? (
              <Empty>Nothing here yet — drop a file above to get started.</Empty>
            ) : (
              <Rows>
                {documents.map((doc) => (
                  <DocumentRow key={doc.id} courseId={id} document={doc} />
                ))}
              </Rows>
            )}
          </section>
        </>
      )}
    </AppShell>
  )
}

function UploadDropzone({
  courseId,
  onUploaded,
}: {
  courseId: string
  onUploaded: (doc: DocumentResponse) => void
}) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [reading, setReading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // A PDF that has been read but not yet sent: Phase 25.1's cut is chosen here, between picking the
  // file and uploading it, which is the only moment at which the page count is known and nothing
  // has been stored yet.
  const [pending, setPending] = useState<{ file: File; pages: number } | null>(null)

  const send = useCallback(
    async (file: File, range?: { firstPage?: number; lastPage?: number }) => {
      setError(null)
      setUploading(true)
      try {
        const doc = await documentsApi.upload(courseId, file, range)
        onUploaded(doc)
        setPending(null)
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Upload failed.')
      } finally {
        setUploading(false)
      }
    },
    [courseId, onUploaded],
  )

  const handleFiles = useCallback(
    async (files: FileList | null) => {
      const file = files?.[0]
      if (!file) return
      setError(null)
      // Only a PDF can be cut, and only a multi-page one is worth asking about. Everything else —
      // a deck, a Word document, a one-page handout — takes the path it took before this phase, so
      // the common upload is still one gesture.
      if (!isPdf(file)) {
        void send(file)
        return
      }
      setReading(true)
      const pages = await pageCountOf(file)
      setReading(false)
      if (pages == null || pages < 2) {
        void send(file)
        return
      }
      setPending({ file, pages })
    },
    [send],
  )

  const busy = uploading || reading

  function onDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setDragging(false)
    if (!busy) void handleFiles(event.dataTransfer.files)
  }

  if (pending) {
    return (
      <div>
        <PageRangePanel
          file={pending.file}
          pages={pending.pages}
          uploading={uploading}
          onCancel={() => {
            setPending(null)
            setError(null)
          }}
          onStart={(range) => void send(pending.file, range)}
        />
        {error && <ErrorText className="mt-3">{error}</ErrorText>}
      </div>
    )
  }

  return (
    <div>
      <div
        role="button"
        tabIndex={0}
        onClick={() => !busy && inputRef.current?.click()}
        onKeyDown={(event) => {
          if ((event.key === 'Enter' || event.key === ' ') && !busy) {
            event.preventDefault()
            inputRef.current?.click()
          }
        }}
        onDragOver={(event) => {
          event.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        className={cx(
          'flex cursor-pointer flex-col items-center justify-center gap-1 rounded-card border border-dashed px-6 py-12 text-center transition duration-150',
          dragging
            ? 'border-accent bg-surface-2'
            : 'border-line bg-surface hover:border-line-strong',
          busy && 'pointer-events-none opacity-60',
        )}
      >
        <p className="m-0 font-display text-[17px] font-bold tracking-[-0.015em] text-ink">
          {uploading ? 'Uploading…' : reading ? 'Reading the file…' : 'Drop a file here'}
        </p>
        {/* Phase 16 widened this from PDF alone. A deck is read as a deck rather than as a PDF
            export of one, which is where its speaker notes and slide titles come from. */}
        <Meta>or click to browse · PDF, PowerPoint or Word, up to 25 MB</Meta>
      </div>
      <input
        ref={inputRef}
        type="file"
        accept="application/pdf,.pdf,.pptx,.docx,application/vnd.openxmlformats-officedocument.presentationml.presentation,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        className="hidden"
        onChange={(event) => {
          void handleFiles(event.target.files)
          // Reset so re-selecting the same file still fires onChange.
          event.target.value = ''
        }}
      />
      {error && <ErrorText className="mt-3">{error}</ErrorText>}
    </div>
  )
}

function isPdf(file: File) {
  return file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf')
}

// Phase 25.1 — which pages of a picked PDF to ingest, and what that is going to cost.
//
// It defaults to the whole document, so the reader who wants everything presses one button and the
// cut is a thing they can ignore. The reason it is offered at all is that a 300-page textbook whose
// student needs chapter four spends minutes and a chunk of a free-tier vision quota reading the
// other fourteen chapters, and every page of those is also a page retrieval has to sift past.
function PageRangePanel({
  file,
  pages,
  uploading,
  onStart,
  onCancel,
}: {
  file: File
  pages: number
  uploading: boolean
  onStart: (range?: { firstPage?: number; lastPage?: number }) => void
  onCancel: () => void
}) {
  const [first, setFirst] = useState(1)
  const [last, setLast] = useState(pages)

  const from = Math.min(first, last)
  const to = Math.max(first, last)
  const selected = to - from + 1
  const whole = from === 1 && to === pages

  return (
    <div className="rounded-card border border-line bg-surface px-6 py-5">
      <Eyebrow>Ready to ingest</Eyebrow>
      <p className="m-0 mt-1 truncate font-mono text-[13px] text-ink">{file.name}</p>
      <Meta className="mt-1 block">
        {pages} pages · {estimateFor(selected)}
      </Meta>

      <div className="mt-4 flex flex-wrap items-end gap-3">
        <NumberField label="From page" value={first} min={1} max={pages} disabled={uploading} onChange={setFirst} />
        <NumberField label="To page" value={last} min={1} max={pages} disabled={uploading} onChange={setLast} />
        <div className="flex flex-1 items-center justify-end gap-2">
          <Button variant="quiet" size="sm" onClick={onCancel} disabled={uploading}>
            Cancel
          </Button>
          <Button
            size="sm"
            disabled={uploading}
            onClick={() =>
              // Sent as "no range at all" when it is the whole document, so an uncut upload is
              // byte-for-byte the request it was before this phase and the row stores null rather
              // than a range that happens to cover everything.
              onStart(whole ? undefined : { firstPage: from, lastPage: to })
            }
          >
            {uploading ? 'Uploading…' : whole ? 'Ingest all pages' : `Ingest pages ${from}–${to}`}
          </Button>
        </div>
      </div>

      {!whole && (
        <Meta className="mt-3 block">
          {selected} of {pages} pages. The whole file is stored either way, so citations still open
          at the page they name.
        </Meta>
      )}
    </div>
  )
}

// A document row that opens to reveal what the document is about. Once ingested, the backend
// has already written a summary and glossary, so this is a read of cached text rather than a
// generation — but it's fetched lazily on first open, so a course with thirty documents doesn't
// fire thirty requests just to render the list.
function DocumentRow({ courseId, document }: { courseId: string; document: DocumentResponse }) {
  const [open, setOpen] = useState(false)
  const [summary, setSummary] = useState<DocumentSummary | null>(null)
  const [loading, setLoading] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const fetched = useRef(false)

  const expandable = document.status === 'READY'

  useEffect(() => {
    if (!open || fetched.current) return
    fetched.current = true
    let active = true
    setLoading(true)
    documentsApi
      .summary(courseId, document.id)
      .then((data) => {
        if (active) setSummary(data)
      })
      .catch((err) => {
        if (active) setError(err instanceof ApiError ? err.message : 'Could not load the summary.')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [open, courseId, document.id])

  async function generate(refresh: boolean) {
    setError(null)
    setGenerating(true)
    try {
      setSummary(await documentsApi.generateSummary(courseId, document.id, refresh))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not generate a summary.')
    } finally {
      setGenerating(false)
    }
  }

  const header = (
    <div className="flex items-center justify-between gap-4 px-5 py-4">
      <div className="flex min-w-0 items-center gap-3">
        {expandable && (
          <span
            aria-hidden
            className={cx(
              'font-mono text-[10px] text-ink-muted transition-transform duration-150',
              open && 'rotate-90',
            )}
          >
            ▶
          </span>
        )}
        <div className="min-w-0">
          <p className="m-0 truncate font-mono text-[13px] text-ink">{document.filename}</p>
          <Meta>
            {formatBytes(document.sizeBytes)}
            {document.pageCount != null && ` · ${document.pageCount} pages`}
            {/* Phase 25.1. Shown only for a cut document, and shown in the source file's own page
                numbers — the stored file is the whole book, so these are the numbers the citation
                viewer opens at. */}
            {document.firstPage != null &&
              ` · ${document.firstPage}–${document.lastPage ?? 'end'}`}
            {/* Shown only when it is not the default: a row that says "English" on every English
                document is a column of noise, and the useful signal is that this one is not. */}
            {document.language === 'BANGLA' && ' · বাংলা'}
          </Meta>
          {/* Phase 25.3. The whole defence of falling back instead of failing is that the fallback
              is visible, so this line is not optional decoration — it is the thing that makes a
              degraded document different from one that quietly answers nothing. */}
          {document.degradedPages > 0 && document.status === 'READY' && (
            <p className="m-0 mt-1 text-[12px] text-warn">
              {document.degradedPages} page{document.degradedPages === 1 ? '' : 's'} took too long
              to read and kept the text extracted from the file.
            </p>
          )}
          {document.status === 'FAILED' && document.errorMessage && (
            <p className="m-0 mt-1 text-[12px] text-bad">{document.errorMessage}</p>
          )}
        </div>
      </div>
      <StatusBadge status={document.status} />
    </div>
  )

  return (
    <Row interactive={expandable}>
      {expandable ? (
        <button
          type="button"
          aria-expanded={open}
          onClick={() => setOpen((current) => !current)}
          className="block w-full cursor-pointer border-0 bg-transparent p-0 text-left"
        >
          {header}
        </button>
      ) : (
        header
      )}

      {/* Phase 25.2 — where the pipeline has got to, polled with the list it is already polling.
          Only while it is moving: a READY row showing 100% would be a bar that never goes away, and
          a FAILED one keeps its number in the badge and its reason in the line above. */}
      {IN_FLIGHT.includes(document.status) && (
        <div className="border-t border-line-soft px-5 py-3">
          <ProgressBar percent={document.progress} label={document.stage} />
        </div>
      )}

      {open && (
        <div className="border-t border-line-soft bg-ground-2 px-5 py-5">
          {loading && <Loading>Reading the summary</Loading>}
          {error && <ErrorText className="mb-3">{error}</ErrorText>}

          {summary && !loading && (
            <>
              {summary.summary ? (
                <>
                  <Eyebrow className="mb-1.5">Summary</Eyebrow>
                  <Markdown
                    text={summary.summary}
                    className="max-w-[70ch] text-sm leading-relaxed text-ink-2"
                  />

                  {summary.terms.length > 0 && (
                    <>
                      <Eyebrow className="mt-6 mb-2">Key terms</Eyebrow>
                      <dl className="m-0 grid gap-x-6 gap-y-2.5 sm:grid-cols-[minmax(0,14rem)_minmax(0,1fr)]">
                        {summary.terms.map((entry) => (
                          <div key={entry.term} className="contents">
                            <dt className="m-0 font-mono text-[12px] text-ink">{entry.term}</dt>
                            <dd className="m-0 mb-2 text-[13px] text-ink-2 sm:mb-0">
                              {entry.definition}
                            </dd>
                          </div>
                        ))}
                      </dl>
                    </>
                  )}

                  <div className="mt-5 flex items-center gap-4 border-t border-line-soft pt-3">
                    <Meta>generated {formatWhen(summary.generatedAt)}</Meta>
                    <Button
                      variant="quiet"
                      onClick={() => void generate(true)}
                      disabled={generating}
                      className="ml-auto"
                    >
                      {generating ? 'Regenerating…' : 'Regenerate'}
                    </Button>
                  </div>
                </>
              ) : (
                // Ordinary for anything ingested before this feature existed, or whose
                // generation failed while the provider was down.
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <p className="m-0 text-sm text-ink-muted">
                    No summary for this document yet.
                  </p>
                  <Button
                    variant="primary"
                    size="sm"
                    onClick={() => void generate(false)}
                    disabled={generating}
                  >
                    {generating ? 'Summarizing…' : 'Summarize it'}
                  </Button>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </Row>
  )
}

function formatWhen(iso: string | null): string {
  if (!iso) return 'just now'
  return new Date(iso).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

function StatusBadge({ status }: { status: DocumentStatus }) {
  const tone =
    status === 'READY' ? 'ok' : status === 'FAILED' ? 'bad' : IN_FLIGHT.includes(status) && status !== 'UPLOADED' ? 'warn' : 'neutral'
  const inFlight = IN_FLIGHT.includes(status)
  return (
    <Pill tone={tone}>
      {inFlight && <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-current" aria-hidden />}
      {status}
    </Pill>
  )
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const kb = bytes / 1024
  if (kb < 1024) return `${kb.toFixed(0)} KB`
  return `${(kb / 1024).toFixed(1)} MB`
}
