import { useCallback, useEffect, useRef, useState, type DragEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError, coursesApi, documentsApi } from '../lib/api'
import type { CourseResponse, DocumentResponse, DocumentStatus } from '../lib/types'
import { AppHeader } from '../components/AppHeader'

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

  return (
    <div className="min-h-screen bg-slate-50 dark:bg-slate-950">
      <AppHeader />
      <main className="mx-auto max-w-3xl px-6 py-10">
        <Link
          to="/"
          className="text-sm text-slate-500 hover:underline dark:text-slate-400"
        >
          ← Your courses
        </Link>

        {loading && <p className="mt-6 text-slate-500 dark:text-slate-400">Loading…</p>}
        {error && <p className="mt-6 text-sm text-red-500">{error}</p>}

        {course && (
          <>
            <h1 className="mt-4 text-2xl font-semibold tracking-tight text-slate-900 dark:text-slate-100">
              {course.name}
            </h1>
            {course.description && (
              <p className="mt-1 text-slate-500 dark:text-slate-400">{course.description}</p>
            )}

            <div className="mt-8">
              <UploadDropzone courseId={id} onUploaded={mergeDocument} />
            </div>

            <h2 className="mt-10 mb-4 text-lg font-semibold tracking-tight text-slate-900 dark:text-slate-100">
              Documents
            </h2>
            {documents.length === 0 ? (
              <p className="text-slate-500 dark:text-slate-400">
                No documents yet — upload a PDF above to get started.
              </p>
            ) : (
              <ul className="flex flex-col gap-3">
                {documents.map((doc) => (
                  <DocumentRow key={doc.id} document={doc} />
                ))}
              </ul>
            )}
          </>
        )}
      </main>
    </div>
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
        className={[
          'flex cursor-pointer flex-col items-center justify-center rounded-xl border-2 border-dashed px-6 py-10 text-center transition',
          dragging
            ? 'border-indigo-500 bg-indigo-50 dark:bg-indigo-950/30'
            : 'border-slate-300 bg-white hover:border-indigo-400 dark:border-slate-700 dark:bg-slate-900',
          uploading ? 'pointer-events-none opacity-60' : '',
        ].join(' ')}
      >
        <p className="font-medium text-slate-700 dark:text-slate-200">
          {uploading ? 'Uploading…' : 'Drop a PDF here, or click to browse'}
        </p>
        <p className="mt-1 text-xs text-slate-400">PDF up to 25 MB</p>
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
      {error && <p className="mt-2 text-sm text-red-500">{error}</p>}
    </div>
  )
}

function DocumentRow({ document }: { document: DocumentResponse }) {
  return (
    <li className="flex items-center justify-between gap-4 rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="min-w-0">
        <p className="truncate font-medium text-slate-900 dark:text-slate-100">
          {document.filename}
        </p>
        <p className="text-xs text-slate-400">
          {formatBytes(document.sizeBytes)}
          {document.pageCount != null && ` · ${document.pageCount} pages`}
        </p>
        {document.status === 'FAILED' && document.errorMessage && (
          <p className="mt-1 text-xs text-red-500">{document.errorMessage}</p>
        )}
      </div>
      <StatusBadge status={document.status} />
    </li>
  )
}

function StatusBadge({ status }: { status: DocumentStatus }) {
  const styles: Record<DocumentStatus, string> = {
    UPLOADED: 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300',
    EXTRACTING: 'bg-amber-100 text-amber-700 dark:bg-amber-950/40 dark:text-amber-300',
    CHUNKING: 'bg-amber-100 text-amber-700 dark:bg-amber-950/40 dark:text-amber-300',
    EMBEDDING: 'bg-amber-100 text-amber-700 dark:bg-amber-950/40 dark:text-amber-300',
    READY: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300',
    FAILED: 'bg-red-100 text-red-700 dark:bg-red-950/40 dark:text-red-300',
  }
  const inFlight = IN_FLIGHT.includes(status)
  return (
    <span
      className={`flex shrink-0 items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium ${styles[status]}`}
    >
      {inFlight && (
        <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-current" aria-hidden />
      )}
      {status}
    </span>
  )
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const kb = bytes / 1024
  if (kb < 1024) return `${kb.toFixed(0)} KB`
  return `${(kb / 1024).toFixed(1)} MB`
}
