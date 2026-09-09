import { useEffect, useRef, useState } from 'react'
import { openPdf, type PickedPdf } from '../lib/pdf'
import { Eyebrow, Meta } from './ui'
import { cx } from '../lib/style'

// Phase 27.1 — the pages a page-ranged upload is about to ingest, drawn from the picked file
// before anything is sent.
//
// **The failure this catches is specific, and nothing else catches it.** A book's printed page
// numbers are not its PDF page numbers: somebody typing "12 to 240" is reading a table of
// contents, and front matter offsets the whole book by ten to twenty pages. Ingest the wrong range
// and the result looks completely healthy — right status, right page count, chunks indexed,
// citations that open — and it indexed the wrong chapter. The only symptom is answers that are
// subtly about the wrong material, months later, with nothing to point at. Two pictures fix it in
// the second before the upload starts.
//
// No upload, no endpoint, no dependency, no provider call: pdf.js is already bundled and already
// parsing this File to read its page count.
export function PdfFilmstrip({
  file,
  pages,
  first,
  last,
  onPickFirst,
  onPickLast,
}: {
  file: File
  pages: number
  first: number
  last: number
  onPickFirst: (page: number) => void
  onPickLast: (page: number) => void
}) {
  const [doc, setDoc] = useState<PickedPdf | null>(null)
  const [failed, setFailed] = useState(false)

  // **One document for the life of the panel, destroyed on unmount.** Every getDocument call
  // spins up a pdf.js worker and re-parses the file, so a document per thumbnail would be a worker
  // per thumbnail and six parses of a 25 MB book. pageCountOf destroys immediately, which is right
  // for a counter and wrong for this.
  useEffect(() => {
    let live: PickedPdf | null = null
    let active = true
    void openPdf(file).then((opened) => {
      if (!active) {
        opened?.destroy()
        return
      }
      live = opened
      setDoc(opened)
      setFailed(opened == null)
    })
    return () => {
      active = false
      live?.destroy()
    }
  }, [file])

  if (failed) {
    // Silent rather than an error. The upload itself still works and the extractor produces the
    // real message if the file is genuinely unreadable; a preview that cannot render is not a
    // reason to block one.
    return null
  }

  return (
    <div className="mt-5 grid gap-6 border-t border-line-soft pt-5 sm:grid-cols-2">
      <Strip doc={doc} pages={pages} centre={first} label="Starts at" onPick={onPickFirst} />
      <Strip doc={doc} pages={pages} centre={last} label="Ends at" onPick={onPickLast} />
      <Meta className="block sm:col-span-2">
        Check these against the chapter you meant. A book's printed page numbers are usually ten to
        twenty pages ahead of its PDF numbers, and an ingest of the wrong range looks completely
        healthy afterwards. Click a neighbour to move the bound.
      </Meta>
    </div>
  )
}

const THUMB_WIDTH = 116

// The bound and the page either side of it — "the pages around each bound, not 296 canvases".
// Three is what makes clicking useful: the neighbours are how a reader nudges the range without
// typing, and rendering the whole book to offer that would be a worker's worth of parsing per
// page for pages nobody looks at.
function Strip({
  doc,
  pages,
  centre,
  label,
  onPick,
}: {
  doc: PickedPdf | null
  pages: number
  centre: number
  label: string
  onPick: (page: number) => void
}) {
  const window = [centre - 1, centre, centre + 1].filter((page) => page >= 1 && page <= pages)

  return (
    <div>
      <Eyebrow>
        {label} page {centre}
      </Eyebrow>
      <div className="mt-2 flex gap-2">
        {window.map((page) => (
          <Thumbnail
            key={page}
            doc={doc}
            page={page}
            selected={page === centre}
            onPick={() => onPick(page)}
          />
        ))}
      </div>
    </div>
  )
}

function Thumbnail({
  doc,
  page,
  selected,
  onPick,
}: {
  doc: PickedPdf | null
  page: number
  selected: boolean
  onPick: () => void
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [drawn, setDrawn] = useState(false)

  // Re-runs when the bound moves, which is what makes typing in the number field feel like
  // turning a page.
  useEffect(() => {
    const canvas = canvasRef.current
    if (!doc || !canvas) return
    let active = true
    setDrawn(false)
    void doc
      .render(page, canvas, THUMB_WIDTH)
      .then(() => {
        if (active) setDrawn(true)
      })
      .catch(() => {
        // A page pdf.js cannot draw leaves the placeholder up. The upload is unaffected.
      })
    return () => {
      active = false
    }
  }, [doc, page])

  return (
    <button
      type="button"
      onClick={onPick}
      title={`Use page ${page}`}
      aria-pressed={selected}
      className={cx(
        'block cursor-pointer overflow-hidden rounded-card border bg-ground-2 p-0 transition duration-150',
        selected ? 'border-accent' : 'border-line opacity-60 hover:opacity-100',
      )}
      style={{ width: THUMB_WIDTH }}
    >
      <canvas
        ref={canvasRef}
        className={cx('block w-full transition-opacity duration-150', drawn ? 'opacity-100' : 'opacity-0')}
        style={{ minHeight: drawn ? undefined : THUMB_WIDTH * 1.3 }}
      />
      <span className="block border-t border-line-soft py-1 text-center font-mono text-[10px] text-ink-muted">
        {page}
      </span>
    </button>
  )
}
