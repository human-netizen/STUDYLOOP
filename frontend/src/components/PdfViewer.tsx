import { useEffect, useState } from 'react'
import { Document, Page, pdfjs } from 'react-pdf'
import 'react-pdf/dist/Page/AnnotationLayer.css'
import 'react-pdf/dist/Page/TextLayer.css'
import { ApiError, documentsApi } from '../lib/api'
import type { Citation } from '../lib/types'

// pdf.js runs its parser in a Web Worker. Vite bundles the worker file and hands us a URL for
// it; pointing pdf.js at that URL keeps everything self-hosted (no CDN fetch).
pdfjs.GlobalWorkerOptions.workerSrc = new URL(
  'pdfjs-dist/build/pdf.worker.min.mjs',
  import.meta.url,
).toString()

// A right-side drawer that renders a cited PDF, opened at the citation's page. Fetches the
// bytes once (auth-guarded), then lets the reader page around. Remounted by the parent (keyed
// on documentId) when a citation points at a different document.
export function PdfViewer({
  courseId,
  citation,
  onClose,
}: {
  courseId: string
  citation: Citation
  onClose: () => void
}) {
  const [fileUrl, setFileUrl] = useState<string | null>(null)
  const [numPages, setNumPages] = useState<number | null>(null)
  const [page, setPage] = useState(citation.pageNumber ?? 1)
  const [error, setError] = useState<string | null>(null)

  // Jump to the cited page whenever the active citation changes (same document, new [n]).
  useEffect(() => {
    setPage(citation.pageNumber ?? 1)
  }, [citation])

  // Load the PDF bytes into an object URL; revoke it on cleanup so we don't leak blobs.
  useEffect(() => {
    let url: string | null = null
    let active = true
    setError(null)
    setFileUrl(null)
    documentsApi
      .fileBlob(courseId, citation.documentId)
      .then((blob) => {
        if (!active) return
        url = URL.createObjectURL(blob)
        setFileUrl(url)
      })
      .catch((err) => {
        if (active) setError(err instanceof ApiError ? err.message : 'Could not load this document.')
      })
    return () => {
      active = false
      if (url) URL.revokeObjectURL(url)
    }
  }, [courseId, citation.documentId])

  // Close on Escape for keyboard users.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const clampedPage = numPages ? Math.min(Math.max(page, 1), numPages) : page

  return (
    <div className="fixed inset-0 z-40 flex justify-end">
      {/* Backdrop */}
      <button
        type="button"
        aria-label="Close viewer"
        onClick={onClose}
        className="absolute inset-0 bg-slate-900/40"
      />
      <aside className="relative z-10 flex h-full w-full max-w-2xl flex-col bg-white shadow-xl dark:bg-slate-900">
        <header className="flex items-center justify-between gap-3 border-b border-slate-200 px-4 py-3 dark:border-slate-800">
          <div className="min-w-0">
            <p className="truncate text-sm font-medium text-slate-900 dark:text-slate-100">
              {citation.filename}
            </p>
            <p className="text-xs text-slate-400">
              Source [{citation.index}]
              {citation.pageNumber != null && ` · cited page ${citation.pageNumber}`}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg border border-slate-300 px-2.5 py-1 text-sm text-slate-600 transition hover:bg-slate-100 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
          >
            Close
          </button>
        </header>

        {numPages != null && (
          <div className="flex items-center justify-center gap-4 border-b border-slate-200 px-4 py-2 text-sm dark:border-slate-800">
            <button
              type="button"
              onClick={() => setPage((p) => Math.max(1, p - 1))}
              disabled={clampedPage <= 1}
              className="rounded px-2 py-1 text-slate-600 disabled:opacity-40 enabled:hover:bg-slate-100 dark:text-slate-300 dark:enabled:hover:bg-slate-800"
            >
              ← Prev
            </button>
            <span className="text-slate-500 dark:text-slate-400">
              Page {clampedPage} of {numPages}
            </span>
            <button
              type="button"
              onClick={() => setPage((p) => Math.min(numPages, p + 1))}
              disabled={clampedPage >= numPages}
              className="rounded px-2 py-1 text-slate-600 disabled:opacity-40 enabled:hover:bg-slate-100 dark:text-slate-300 dark:enabled:hover:bg-slate-800"
            >
              Next →
            </button>
          </div>
        )}

        <div className="flex-1 overflow-auto bg-slate-100 p-4 dark:bg-slate-950">
          {error && <p className="text-sm text-red-500">{error}</p>}
          {!error && !fileUrl && <p className="text-sm text-slate-500 dark:text-slate-400">Loading document…</p>}
          {fileUrl && (
            <Document
              file={fileUrl}
              onLoadSuccess={({ numPages: n }) => setNumPages(n)}
              onLoadError={(e) => setError(e.message)}
              loading={<p className="text-sm text-slate-500 dark:text-slate-400">Loading document…</p>}
              error={<p className="text-sm text-red-500">Failed to render this PDF.</p>}
              className="flex justify-center"
            >
              <Page
                pageNumber={clampedPage}
                width={560}
                renderAnnotationLayer={false}
                className="shadow"
              />
            </Document>
          )}
        </div>
      </aside>
    </div>
  )
}
