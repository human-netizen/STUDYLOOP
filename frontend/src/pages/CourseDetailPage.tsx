import { useCallback, useEffect, useRef, useState, type DragEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError, coursesApi, documentsApi, errorMessage } from '../lib/api'
import type {
  CourseResponse,
  DocumentCategory,
  DocumentImpact,
  DocumentResponse,
  DocumentStatus,
  DocumentSummary,
  DocumentTaxonomyRequest,
  MemberResponse,
} from '../lib/types'
import { AppShell } from '../components/AppShell'
import { Markdown } from '../components/Markdown'
import {
  Button,
  Confirm,
  Empty,
  ErrorText,
  Eyebrow,
  Field,
  Input,
  Loading,
  Meta,
  NumberField,
  PageTitle,
  Pill,
  ProgressBar,
  Row,
  Rows,
  SectionHead,
  Select,
  TextArea,
} from '../components/ui'
import { PdfFilmstrip } from '../components/PdfFilmstrip'
import { useJobWatch } from '../lib/jobs'
import { estimateFor, pageCountOf } from '../lib/pdf'
import { cx, linkButton } from '../lib/style'

// Statuses that are still moving through the pipeline — while any document sits in one of
// these, we re-poll the list so the UI tracks it to READY/FAILED.
const IN_FLIGHT: DocumentStatus[] = ['UPLOADED', 'EXTRACTING', 'CHUNKING', 'EMBEDDING']
const POLL_INTERVAL_MS = 2500

export function CourseDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()

  const [course, setCourse] = useState<CourseResponse | null>(null)
  const [documents, setDocuments] = useState<DocumentResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [managing, setManaging] = useState(false)
  const { watch } = useJobWatch()

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
  const mergeDocument = useCallback(
    (doc: DocumentResponse) => {
      setDocuments((current) => {
        const index = current.findIndex((existing) => existing.id === doc.id)
        if (index === -1) return [doc, ...current]
        const next = current.slice()
        next[index] = doc
        return next
      })
      // Phase 29.4. Every ingest starts here — an upload and a re-ingest both land in this one
      // function — so this is the only place that has to know a long job began. The page's own
      // poll above keeps the row moving while you are looking at it; the watch is what carries on
      // when you are not.
      if (IN_FLIGHT.includes(doc.status)) {
        watch({ kind: 'document', courseId: id, id: doc.id, label: doc.filename })
      }
    },
    [id, watch],
  )

  // Phase 27.3. Dropped from the list rather than re-fetched: the row is gone, and a poll to
  // confirm it would be a request whose only possible answer is the one we already have.
  const dropDocument = useCallback((documentId: string) => {
    setDocuments((current) => current.filter((doc) => doc.id !== documentId))
  }, [])

  const canManage = course?.myRole === 'OWNER' || course?.myRole === 'INSTRUCTOR'
  const readyCount = documents.filter((doc) => doc.status === 'READY').length
  const retiredCount = documents.filter((doc) => doc.status === 'RETIRED').length

  return (
    <AppShell courseName={course?.name}>
      {loading && <Loading />}
      {error && <ErrorText>{error}</ErrorText>}

      {course && (
        <>
          <PageTitle
            eyebrow={course.archivedAt ? `${course.myRole} · archived` : course.myRole}
            title={course.name}
            sub={course.description}
            action={
              <div className="flex items-center gap-2">
                <Button variant="quiet" size="sm" onClick={() => setManaging((open) => !open)}>
                  {managing ? 'Done' : 'Manage'}
                </Button>
                <Link to={`/courses/${id}/chat`} className={cx(linkButton('primary'), 'no-underline')}>
                  Ask this course
                </Link>
              </div>
            }
          />

          {/* Phase 27.4 — rename, archive, the member list and leaving. Behind a toggle rather
              than always on the page: they are the verbs somebody reaches for twice a semester,
              and a course page whose first row is "Delete" reads as a settings screen. */}
          {managing && (
            <CoursePanel
              course={course}
              onChanged={setCourse}
              onLeft={() => navigate('/courses')}
            />
          )}

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
                  ? `${readyCount} of ${documents.length} ready to answer questions` +
                    (retiredCount > 0 ? ` · ${retiredCount} retired` : '')
                  : undefined
              }
            />
            {documents.length === 0 ? (
              <Empty>Nothing here yet — drop a file above to get started.</Empty>
            ) : (
              <Rows>
                {documents.map((doc) => (
                  <DocumentRow
                    key={doc.id}
                    courseId={id}
                    document={doc}
                    canManage={canManage}
                    /* Phase 29.2. The name is resolved here rather than sent with the row: this
                       list already holds every document in the course, so asking the server to
                       join for a filename we have would be a query per row for nothing. Null when
                       the match points at a document this member cannot see. */
                    resembles={
                      documents.find((other) => other.id === doc.nearDuplicateOfId)?.filename ?? null
                    }
                    onChanged={mergeDocument}
                    onDeleted={dropDocument}
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

      {/* Phase 27.1 — the two pages this is about to read, drawn from the file itself. It is
          above the fields rather than below them because it is the thing being decided; the
          numbers are how the decision is expressed. */}
      <PdfFilmstrip
        file={file}
        pages={pages}
        first={from}
        last={to}
        onPickFirst={setFirst}
        onPickLast={setLast}
      />

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
function DocumentRow({
  courseId,
  document,
  canManage,
  resembles,
  onChanged,
  onDeleted,
}: {
  courseId: string
  document: DocumentResponse
  canManage: boolean
  resembles: string | null
  onChanged: (doc: DocumentResponse) => void
  onDeleted: (documentId: string) => void
}) {
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
          {/* Phase 23.2 — how this material is filed, and the reason it is on the row rather than
              behind the expander: it is what a question naming a week will be answered from, so a
              reader who gets a narrowed answer needs to see why without opening anything.
              UNCLASSIFIED is deliberately not shown — a badge on every unlabelled document is a
              column of noise, and the signal worth having is the label somebody chose. */}
          {(document.week != null ||
            document.category !== 'UNCLASSIFIED' ||
            document.tags.length > 0) && (
            <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
              {document.week != null && <Pill tone="accent">Week {document.week}</Pill>}
              {document.category !== 'UNCLASSIFIED' && (
                <Pill>{CATEGORY_LABELS[document.category]}</Pill>
              )}
              {document.tags.map((tag) => (
                <Pill key={tag} tone="neutral">
                  {tag}
                </Pill>
              ))}
            </div>
          )}
          {/* Phase 25.3. The whole defence of falling back instead of failing is that the fallback
              is visible, so this line is not optional decoration — it is the thing that makes a
              degraded document different from one that quietly answers nothing.

              Reworded in Phase 23.6 (2026-09-11), and the old wording is the reason to be careful
              here: it said "took too long to read", which was exactly true while a timeout was the
              only thing that could degrade a page. Now a page also degrades when the model answers
              about it and says nothing usable — a safety block, an empty response, no text read —
              so naming the timeout would be wrong on most degraded pages. The copy therefore states
              the consequence, which is the same whatever the cause and is the part a reader can act
              on: this page is indexed from weaker text, so an answer citing it may be thin. */}
          {document.degradedPages > 0 && document.status === 'READY' && (
            <p className="m-0 mt-1 text-[12px] text-warn">
              {document.degradedPages} page{document.degradedPages === 1 ? '' : 's'} could not be
              read by the vision model and {document.degradedPages === 1 ? 'is' : 'are'} indexed
              from the text extracted directly from the file.
            </p>
          )}
          {document.status === 'FAILED' && document.errorMessage && (
            <p className="m-0 mt-1 text-[12px] text-bad">{document.errorMessage}</p>
          )}
          {/* Phase 29.2 — said, never acted on. The upload was accepted, the document answers
              questions, and this is a remark a person can act on or ignore. Worded as an
              observation rather than a warning for that reason: two similar lectures are a
              legitimate thing for a course to have, and the reader is the one who knows which
              this is. */}
          {resembles && document.nearDuplicateScore != null && (
            <p className="m-0 mt-1 text-[12px] text-ink-2">
              Looks like <span className="font-mono">{resembles}</span> —{' '}
              {Math.round(document.nearDuplicateScore * 100)}% of the passages sampled here are
              already in it.
            </p>
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

      {/* Phase 27.2 and 27.3. Hidden while the pipeline is running, because every verb here
          either restarts it or removes what it is building. */}
      {canManage && !IN_FLIGHT.includes(document.status) && (
        <DocumentActions
          courseId={courseId}
          document={document}
          onChanged={onChanged}
          onDeleted={onDeleted}
        />
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
  // RETIRED falls through to neutral, which is the right weight for it: nothing is wrong, the
  // document is simply not answering questions. A warn tone would read as a problem to fix.
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

// Phase 27.2 and 27.3 — the three things a manager can now do to a document that is already here.
//
// They sit together because they are one decision with three answers, in ascending cost: read it
// again (a different cut, or a retry after a failure), take it out of answers but keep it, or
// destroy it. Offering them in that order is the point — the cheap ones are what somebody
// reaching for "delete" usually meant.
function DocumentActions({
  courseId,
  document,
  onChanged,
  onDeleted,
}: {
  courseId: string
  document: DocumentResponse
  onChanged: (doc: DocumentResponse) => void
  onDeleted: (documentId: string) => void
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [recutting, setRecutting] = useState(false)
  const [labelling, setLabelling] = useState(false)
  // undefined = not fetched yet, which is what the Confirm waits on before it will commit.
  const [impact, setImpact] = useState<DocumentImpact | undefined>(undefined)

  const retired = document.status === 'RETIRED'

  async function run<T>(action: () => Promise<T>, then: (result: T) => void) {
    setError(null)
    setBusy(true)
    try {
      then(await action())
    } catch (err) {
      setError(errorMessage(err, 'That did not work.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="border-t border-line-soft px-5 py-3">
      <div className="flex flex-wrap items-center justify-end gap-2">
        {/* Phase 23.2 — the correction path, which had to exist before the inference was
            allowed to guess anything. It sits with the other verbs rather than in the expander,
            because it is a manager action on the row and the expander is a reader's summary. */}
        {!recutting && !labelling && (
          <Button variant="quiet" size="sm" disabled={busy} onClick={() => setLabelling(true)}>
            Label
          </Button>
        )}

        {/* Re-ingest. Named for what it costs the reader rather than for what it does to the
            database: "Read it again" is the sentence somebody who picked the wrong pages is
            already saying. */}
        {!recutting && (
          <Button
            variant="quiet"
            size="sm"
            disabled={busy}
            onClick={() => setRecutting(true)}
            className="mr-auto"
          >
            Read it again
          </Button>
        )}

        {retired ? (
          <Button
            variant="ghost"
            size="sm"
            disabled={busy}
            onClick={() => void run(() => documentsApi.unretire(courseId, document.id), onChanged)}
          >
            {busy ? 'Restoring…' : 'Put it back'}
          </Button>
        ) : (
          document.status === 'READY' && (
            <Button
              variant="quiet"
              size="sm"
              disabled={busy}
              onClick={() => void run(() => documentsApi.retire(courseId, document.id), onChanged)}
            >
              {busy ? 'Retiring…' : 'Retire'}
            </Button>
          )
        )}

        <Confirm
          label="Delete"
          question={`Delete ${document.filename}? This cannot be undone.`}
          detail={impact === undefined ? undefined : describeImpact(impact)}
          busy={busy}
          // Fetched when the confirmation opens, never with the list: it is six counts per
          // document, and nobody is deleting the other twenty-nine.
          onOpen={() => {
            setImpact(undefined)
            documentsApi
              .impact(courseId, document.id)
              .then(setImpact)
              .catch(() => setImpact(null as unknown as DocumentImpact))
          }}
          onConfirm={() =>
            void run(
              () => documentsApi.remove(courseId, document.id),
              () => onDeleted(document.id),
            )
          }
        />
      </div>

      {labelling && (
        <LabelPanel
          document={document}
          busy={busy}
          onCancel={() => setLabelling(false)}
          onSave={(taxonomy) =>
            void run(
              () => documentsApi.setTaxonomy(courseId, document.id, taxonomy),
              (doc) => {
                onChanged(doc)
                setLabelling(false)
              },
            )
          }
        />
      )}

      {recutting && (
        <RecutPanel
          document={document}
          busy={busy}
          onCancel={() => setRecutting(false)}
          onStart={(range) =>
            void run(
              () => documentsApi.reingest(courseId, document.id, range),
              (doc) => {
                onChanged(doc)
                setRecutting(false)
              },
            )
          }
        />
      )}

      {/* Retiring is the reversible one, and saying so is what makes it the one people reach for
          instead of the other. Shown only on a retired row, where it is the answer to "is this
          gone?". */}
      {retired && (
        <Meta className="mt-2 block">
          Out of every answer, quiz and flashcard. The file, its passages and its vectors are all
          still here, and citations handed out earlier still open.
        </Meta>
      )}

      {error && <ErrorText className="mt-2">{error}</ErrorText>}
    </div>
  )
}

// The page range for a re-ingest. Defaults to the range the document already has, so pressing the
// button twice is a plain retry — which is what somebody whose ingest failed on a provider outage
// actually wants — and changing the numbers is what makes it a re-cut.
//
// No filmstrip here, deliberately. The bytes are on the server, and drawing thumbnails would mean
// downloading the whole file back to the browser to preview a decision the reader has already
// made once. 27.1's argument is about a file that has not been uploaded yet.
// Phase 23.2 — the human half of the taxonomy: what the file is, said by somebody who knows.
//
// **A form that submits all three fields together, which is why the endpoint is a PUT.** The
// alternative — a PATCH per field with `null` meaning "leave alone" — would have no way to spell
// "this is not a week 4 document after all", and a set of tags has two readings under a partial
// update that nothing on the wire can tell apart.
//
// The week is a free number rather than a picker over the course's existing weeks, deliberately:
// the first document of week 5 has to be able to say so, and a control that only offers what
// already exists cannot create anything.
function LabelPanel({
  document,
  busy,
  onSave,
  onCancel,
}: {
  document: DocumentResponse
  busy: boolean
  onSave: (taxonomy: DocumentTaxonomyRequest) => void
  onCancel: () => void
}) {
  const [week, setWeek] = useState<number | undefined>(document.week ?? undefined)
  const [category, setCategory] = useState<DocumentCategory>(document.category)
  // Edited as the comma-separated line people actually type. Normalisation — lower case, single
  // spaces, duplicates gone — is the server's, in one function, because it has to be the same
  // rule for the write and for any later comparison against a stored tag.
  const [tags, setTags] = useState(document.tags.join(', '))

  return (
    <div className="mt-3 border-t border-line-soft pt-3">
      <Eyebrow className="mb-2">How is this filed?</Eyebrow>
      <div className="flex flex-wrap items-end gap-3">
        {/* A bare Input rather than NumberField, and the reason is the empty case. NumberField
            clamps a blank box to its minimum, which is right for "how many questions" and wrong
            here: a document that belongs to no week has to be expressible, and clamping would
            make clearing the week set it to 1. */}
        <Field label="Week" className="w-24 shrink-0">
          <Input
            type="number"
            min={1}
            max={52}
            value={week ?? ''}
            disabled={busy}
            placeholder="—"
            className="tnum font-mono"
            onChange={(event) => {
              const next = event.target.value
              setWeek(next === '' ? undefined : Number(next))
            }}
          />
        </Field>
        <Field label="Kind">
          <Select
            value={category}
            disabled={busy}
            onChange={(event) => setCategory(event.target.value as DocumentCategory)}
          >
            {CATEGORY_ORDER.map((value) => (
              <option key={value} value={value}>
                {CATEGORY_LABELS[value]}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Tags" className="min-w-[16rem] flex-1">
          <Input
            value={tags}
            disabled={busy}
            placeholder="hash tables, probing"
            onChange={(event) => setTags(event.target.value)}
          />
        </Field>
      </div>

      <Meta className="mt-2 block">
        A week and a kind are what a question like “what did week 3 cover” is answered from. Tags
        are for finding things here.
      </Meta>

      <div className="mt-3 flex justify-end gap-2">
        <Button variant="ghost" size="sm" disabled={busy} onClick={onCancel}>
          Cancel
        </Button>
        <Button
          size="sm"
          disabled={busy}
          onClick={() =>
            onSave({
              week: week ?? null,
              category,
              tags: tags
                .split(',')
                .map((tag) => tag.trim())
                .filter((tag) => tag.length > 0),
            })
          }
        >
          {busy ? 'Saving…' : 'Save'}
        </Button>
      </div>
    </div>
  )
}

// UNCLASSIFIED last and named for what it is, so the dropdown's default reads as an admission
// rather than as a choice somebody made.
const CATEGORY_ORDER: DocumentCategory[] = [
  'LECTURE',
  'LAB',
  'TUTORIAL',
  'ASSIGNMENT',
  'EXAM',
  'READING',
  'UNCLASSIFIED',
]

const CATEGORY_LABELS: Record<DocumentCategory, string> = {
  LECTURE: 'Lecture',
  LAB: 'Lab',
  TUTORIAL: 'Tutorial',
  ASSIGNMENT: 'Assignment',
  EXAM: 'Exam',
  READING: 'Reading',
  UNCLASSIFIED: 'Not filed',
}

function RecutPanel({
  document,
  busy,
  onStart,
  onCancel,
}: {
  document: DocumentResponse
  busy: boolean
  onStart: (range?: { firstPage?: number; lastPage?: number }) => void
  onCancel: () => void
}) {
  // A document whose ingest failed before it was parsed has no page count, so there is no real
  // upper bound to offer. The cap is deliberately loose rather than invented: PageRange checks the
  // range against the true count at extraction, which is the only place that knows it, and a first
  // page past the end fails the document with a sentence rather than being guessed at here.
  const known = document.pageCount
  const cap = known ?? 10000
  const [first, setFirst] = useState(document.firstPage ?? 1)
  const [last, setLast] = useState(document.lastPage ?? known ?? 1)

  const from = Math.min(first, last)
  const to = Math.max(first, last)
  const whole = from === 1 && known != null && to >= known

  return (
    <div className="mt-3 rounded-card border border-line bg-ground-2 px-4 py-3">
      <Eyebrow>Read it again</Eyebrow>
      <Meta className="mt-1 block">
        The whole file was stored at upload, so this needs no second upload — it re-reads the bytes
        already here. The passages, summary and glossary are replaced, not added to.
      </Meta>
      <div className="mt-3 flex flex-wrap items-end gap-3">
        <NumberField label="From page" value={first} min={1} max={cap} disabled={busy} onChange={setFirst} />
        <NumberField label="To page" value={last} min={1} max={cap} disabled={busy} onChange={setLast} />
        <div className="flex flex-1 items-center justify-end gap-2">
          <Button variant="quiet" size="sm" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button
            variant="primary"
            size="sm"
            disabled={busy}
            onClick={() => onStart(whole ? undefined : { firstPage: from, lastPage: to })}
          >
            {busy ? 'Starting…' : whole ? 'Re-read all pages' : `Re-read pages ${from}–${to}`}
          </Button>
        </div>
      </div>
    </div>
  )
}

// "412 indexed passages · 7 flashcards lose their source". Mirrors DocumentImpact.describe on the
// server; both exist because the sentence is assembled where the numbers are, and the client has
// them here already from the same response.
function describeImpact(impact: DocumentImpact | null): string {
  if (!impact) return 'Could not count what this would affect.'
  const parts = [`${impact.chunks} indexed passage${impact.chunks === 1 ? '' : 's'}`]
  if (impact.flashcards > 0) {
    parts.push(`${impact.flashcards} flashcard${impact.flashcards === 1 ? '' : 's'} lose their source`)
  }
  if (impact.questions > 0) {
    parts.push(
      `${impact.questions} question${impact.questions === 1 ? '' : 's'} lose their lecture attribution`,
    )
  }
  if (impact.forumAnswers > 0) {
    parts.push(
      `${impact.forumAnswers} forum answer${impact.forumAnswers === 1 ? '' : 's'} lose their provenance`,
    )
  }
  if (impact.videos > 0) {
    parts.push(
      `${impact.videos} video${impact.videos === 1 ? '' : 's'} lose ${impact.sceneCitations} scene citation${impact.sceneCitations === 1 ? '' : 's'}`,
    )
  }
  return parts.join(' · ')
}

// Phase 27.4 — rename, archive, who is in here, and the way out.
//
// One panel rather than a settings page, because there are four verbs and three of them are one
// line each. The member list is the exception and earns its space: "remove a member" is not an
// action anybody can take against a list they cannot see.
function CoursePanel({
  course,
  onChanged,
  onLeft,
}: {
  course: CourseResponse
  onChanged: (course: CourseResponse) => void
  onLeft: () => void
}) {
  const [name, setName] = useState(course.name)
  const [description, setDescription] = useState(course.description ?? '')
  const [members, setMembers] = useState<MemberResponse[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  const isOwner = course.myRole === 'OWNER'
  const canManage = isOwner || course.myRole === 'INSTRUCTOR'

  useEffect(() => {
    let active = true
    coursesApi
      .members(course.id)
      .then((rows) => {
        if (active) setMembers(rows)
      })
      .catch(() => {
        if (active) setMembers([])
      })
    return () => {
      active = false
    }
  }, [course.id])

  async function run<T>(action: () => Promise<T>, then: (result: T) => void) {
    setError(null)
    setBusy(true)
    try {
      then(await action())
    } catch (err) {
      setError(errorMessage(err, 'That did not work.'))
    } finally {
      setBusy(false)
    }
  }

  // Only the fields that changed go in the body — that is what makes it a PATCH. Sending both
  // every time would work and would also mean a rename silently rewriting a description somebody
  // else had edited in another tab.
  function save() {
    const body: { name?: string; description?: string } = {}
    if (name !== course.name) body.name = name
    if (description !== (course.description ?? '')) body.description = description
    if (Object.keys(body).length === 0) return
    void run(
      () => coursesApi.update(course.id, body),
      (updated) => {
        onChanged(updated)
        setSaved(true)
      },
    )
  }

  return (
    <div className="mb-12 rounded-card border border-line bg-surface px-6 py-5">
      <Eyebrow>Course settings</Eyebrow>

      {isOwner ? (
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          <Field label="Name">
            <Input
              value={name}
              disabled={busy}
              onChange={(event) => {
                setName(event.target.value)
                setSaved(false)
              }}
            />
          </Field>
          <Field label="Description">
            <TextArea
              rows={2}
              value={description}
              disabled={busy}
              onChange={(event) => {
                setDescription(event.target.value)
                setSaved(false)
              }}
            />
          </Field>
        </div>
      ) : (
        <Meta className="mt-2 block">Only the owner can rename or archive a course.</Meta>
      )}

      <div className="mt-4 flex flex-wrap items-center gap-2">
        {isOwner && (
          <>
            <Button variant="primary" size="sm" disabled={busy} onClick={save}>
              {busy ? 'Saving…' : saved ? 'Saved' : 'Save'}
            </Button>
            {/* Archiving destroys nothing, so it is a plain button and not a confirmation. That
                asymmetry is the feature: the reversible verb should be easier to press. */}
            <Button
              variant="ghost"
              size="sm"
              disabled={busy}
              onClick={() =>
                void run(
                  () =>
                    course.archivedAt
                      ? coursesApi.unarchive(course.id)
                      : coursesApi.archive(course.id),
                  onChanged,
                )
              }
            >
              {course.archivedAt ? 'Un-archive' : 'Archive'}
            </Button>
          </>
        )}
        <div className="ml-auto">
          <Confirm
            label="Leave this course"
            question="Leave this course?"
            detail="Your uploads, answers and questions stay with the course. You will need a new invite to come back."
            confirmLabel="Leave"
            busy={busy}
            onConfirm={() => void run(() => coursesApi.leave(course.id), onLeft)}
          />
        </div>
      </div>

      {course.archivedAt && (
        <Meta className="mt-3 block">
          Archived — hidden from your course list. Everything in it is untouched, and it still
          answers questions.
        </Meta>
      )}

      <Eyebrow className="mt-6 mb-2">Members</Eyebrow>
      {members == null ? (
        <Loading />
      ) : (
        <Rows>
          {members.map((member) => (
            <Row key={member.userId}>
              <div className="flex items-center justify-between gap-4 px-4 py-2.5">
                <div className="min-w-0">
                  <p className="m-0 truncate text-[13px] text-ink">{member.displayName}</p>
                  <Meta>{member.email}</Meta>
                </div>
                <div className="flex items-center gap-3">
                  <Pill tone={member.role === 'MEMBER' ? 'neutral' : 'accent'}>{member.role}</Pill>
                  {canManage && (
                    <Confirm
                      label="Remove"
                      question={`Remove ${member.displayName} from this course?`}
                      detail="Everything they contributed stays. They lose access until they are invited again."
                      confirmLabel="Remove"
                      busy={busy}
                      onConfirm={() =>
                        void run(
                          () => coursesApi.removeMember(course.id, member.userId),
                          () =>
                            setMembers((current) =>
                              (current ?? []).filter((row) => row.userId !== member.userId),
                            ),
                        )
                      }
                    />
                  )}
                </div>
              </div>
            </Row>
          ))}
        </Rows>
      )}

      {error && <ErrorText className="mt-3">{error}</ErrorText>}
    </div>
  )
}
