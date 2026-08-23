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
  PageTitle,
  Pill,
  Row,
  Rows,
  SectionHead,
} from '../components/ui'
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
  const [error, setError] = useState<string | null>(null)

  const handleFiles = useCallback(
    async (files: FileList | null) => {
      const file = files?.[0]
      if (!file) return
      setError(null)
      setUploading(true)
      try {
        const doc = await documentsApi.upload(courseId, file)
        onUploaded(doc)
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Upload failed.')
      } finally {
        setUploading(false)
      }
    },
    [courseId, onUploaded],
  )

  function onDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setDragging(false)
    if (!uploading) void handleFiles(event.dataTransfer.files)
  }

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
          uploading && 'pointer-events-none opacity-60',
        )}
      >
        <p className="m-0 font-display text-[17px] font-bold tracking-[-0.015em] text-ink">
          {uploading ? 'Uploading…' : 'Drop a file here'}
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
            {/* Shown only when it is not the default: a row that says "English" on every English
                document is a column of noise, and the useful signal is that this one is not. */}
            {document.language === 'BANGLA' && ' · বাংলা'}
          </Meta>
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
