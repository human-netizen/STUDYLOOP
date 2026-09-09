import { useEffect, useState } from 'react'
import { Document, Page } from 'react-pdf'
import 'react-pdf/dist/Page/AnnotationLayer.css'
import 'react-pdf/dist/Page/TextLayer.css'
import { ApiError, documentsApi } from '../lib/api'
// Imported for its side effect as much as for nothing else: lib/pdf points pdf.js at the bundled
// worker. It moved out of this file in Phase 25.1, when reading a picked file's page count before
// upload made this the second caller and a worker path set in two modules a path that drifts.
import '../lib/pdf'
import { Button, ErrorText, Eyebrow, Loading, Meta } from './ui'

// A place in a document to open at. A Citation satisfies this, and so does a search hit — the
// viewer needs a file and a page, not the chunk text behind them.
export interface PdfTarget {
  documentId: string
  filename: string
  pageNumber: number | null
  // The [n] marker, when the target came from a citation. Search results carry no number.
  index?: number
}

// A citation the reader can actually open, or null when there is nothing to open (Phase
// 27.3). A video scene whose source document was deleted still carries its citation — the
// filename, page and passage were snapshotted when the scene was written — but the document
// id is gone, and offering a click-through would be a 404 dressed as a source.
//
// A function rather than a cast at each call site: this is the one place that decides what
// makes a citation openable, and the type system now asks the question at every caller.
export function pdfTargetOf(citation: {
  documentId: string | null
  filename: string
  pageNumber: number | null
  index?: number
}): PdfTarget | null {
  if (citation.documentId == null) return null
  return {
    documentId: citation.documentId,
    filename: citation.filename,
    pageNumber: citation.pageNumber,
    index: citation.index,
  }
}

// A right-side drawer that renders a PDF, opened at the target's page. Fetches the bytes once
// (auth-guarded), then lets the reader page around. Remounted by the parent (keyed on
// documentId) when the target points at a different document.
export function PdfViewer({
  courseId,
  target,
  onClose,
}: {
  courseId: string
  target: PdfTarget
  onClose: () => void
}) {
  const [fileUrl, setFileUrl] = useState<string | null>(null)
  const [numPages, setNumPages] = useState<number | null>(null)
  const [page, setPage] = useState(target.pageNumber ?? 1)
  const [error, setError] = useState<string | null>(null)

  // Jump to the new page whenever the target changes within the same document (another [n], or
  // another passage in the same lecture).
  useEffect(() => {
    setPage(target.pageNumber ?? 1)
  }, [target])

  // Load the PDF bytes into an object URL; revoke it on cleanup so we don't leak blobs.
  useEffect(() => {
    let url: string | null = null
    let active = true
    setError(null)
    setFileUrl(null)
    documentsApi
      .fileBlob(courseId, target.documentId)
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
  }, [courseId, target.documentId])

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
        className="absolute inset-0 cursor-default border-0 bg-scrim"
      />
      <aside className="relative z-10 flex h-full w-full max-w-2xl flex-col border-l border-line bg-surface">
        <header className="flex items-center justify-between gap-3 border-b border-line px-5 py-3">
          <div className="min-w-0">
            <Eyebrow>{target.index != null ? `Source [${target.index}]` : 'Source'}</Eyebrow>
            <p className="m-0 truncate font-mono text-[13px] text-ink">{target.filename}</p>
          </div>
          <Button size="sm" onClick={onClose}>
            Close
          </Button>
        </header>

        {numPages != null && (
          <div className="flex items-center justify-center gap-5 border-b border-line px-5 py-2">
            <Button
              variant="quiet"
              onClick={() => setPage((p) => Math.max(1, p - 1))}
              disabled={clampedPage <= 1}
            >
              ← Prev
            </Button>
            <Meta>
              Page {clampedPage} / {numPages}
              {target.pageNumber != null && ` · opened at ${target.pageNumber}`}
            </Meta>
            <Button
              variant="quiet"
              onClick={() => setPage((p) => Math.min(numPages, p + 1))}
              disabled={clampedPage >= numPages}
            >
              Next →
            </Button>
          </div>
        )}

        <div className="flex-1 overflow-auto bg-ground-2 p-4">
          {error && <ErrorText>{error}</ErrorText>}
          {!error && !fileUrl && <Loading>Loading document</Loading>}
          {fileUrl && (
            <Document
              file={fileUrl}
              onLoadSuccess={({ numPages: n }) => setNumPages(n)}
              onLoadError={(e) => setError(e.message)}
              loading={<Loading>Loading document</Loading>}
              error={<ErrorText>Failed to render this PDF.</ErrorText>}
              className="flex justify-center"
            >
              <Page
                pageNumber={clampedPage}
                width={560}
                renderAnnotationLayer={false}
                className="shadow-card"
              />
            </Document>
          )}
        </div>
      </aside>
    </div>
  )
}
