import { useCallback, useEffect, useRef, useState, type DragEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError, coursesApi, documentsApi } from '../lib/api'
import type { CourseResponse, DocumentResponse, DocumentStatus } from '../lib/types'
import { AppShell } from '../components/AppShell'
import {
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
              title="Add a PDF"
              description="Uploads are extracted, chunked and embedded before they can be asked about."
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
              <Empty>Nothing here yet — drop a PDF above to get started.</Empty>
            ) : (
              <Rows>
                {documents.map((doc) => (
                  <DocumentRow key={doc.id} document={doc} />
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
          {uploading ? 'Uploading…' : 'Drop a PDF here'}
        </p>
        <Meta>or click to browse · PDF up to 25 MB</Meta>
      </div>
      <input
        ref={inputRef}
        type="file"
        accept="application/pdf,.pdf"
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

function DocumentRow({ document }: { document: DocumentResponse }) {
  return (
    <Row className="flex items-center justify-between gap-4 px-5 py-4">
      <div className="min-w-0">
        <p className="m-0 truncate font-mono text-[13px] text-ink">{document.filename}</p>
        <Meta>
          {formatBytes(document.sizeBytes)}
          {document.pageCount != null && ` · ${document.pageCount} pages`}
        </Meta>
        {document.status === 'FAILED' && document.errorMessage && (
          <p className="m-0 mt-1 text-[12px] text-bad">{document.errorMessage}</p>
        )}
      </div>
      <StatusBadge status={document.status} />
    </Row>
  )
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
