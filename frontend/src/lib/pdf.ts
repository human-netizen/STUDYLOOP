import { pdfjs } from 'react-pdf'

// pdf.js runs its parser in a Web Worker. Vite bundles the worker file and hands us a URL for it;
// pointing pdf.js at that URL keeps everything self-hosted (no CDN fetch).
//
// Set here rather than in the viewer, because Phase 25.1 gave pdf.js a second caller — reading a
// picked file's page count before it is uploaded — and a worker path configured in two modules is
// a worker path that drifts in one of them.
pdfjs.GlobalWorkerOptions.workerSrc = new URL(
  'pdfjs-dist/build/pdf.worker.min.mjs',
  import.meta.url,
).toString()

// How many pages a picked PDF has, read in the browser from the File the user just chose.
//
// **This is why the page-range control needs no server round trip.** Parsing a PDF's page tree
// touches the trailer and the catalogue, not the content streams, so it is milliseconds on a
// 25 MB book and nothing is uploaded to find out. The alternative — upload, have the server score
// the pages, then ask — would send the file twice and leave a half-started document behind if the
// reader closed the tab.
//
// Returns null rather than throwing when the file cannot be parsed: an encrypted or malformed PDF
// should fall through to the ordinary upload, where the extractor produces the real error message,
// not be refused by a page counter.
export async function pageCountOf(file: File): Promise<number | null> {
  try {
    const bytes = new Uint8Array(await file.arrayBuffer())
    const doc = await pdfjs.getDocument({ data: bytes }).promise
    const pages = doc.numPages
    // pdf.js holds the parsed document and its worker transport open until it is told not to.
    void doc.destroy()
    return pages > 0 ? pages : null
  } catch {
    return null
  }
}

// Phase 27.1 - the same parse, kept open.
//
// **pageCountOf destroys the document immediately, which is correct for a counter and wrong for a
// filmstrip.** Every getDocument call spins up a pdf.js worker and re-parses the file; rendering
// six thumbnails that way would be six workers and six parses of a 25 MB book. So the picker opens
// the file once, holds the handle for as long as the panel is on screen, and destroys it on
// unmount.
//
// Returns null on a file pdf.js cannot parse, for the same reason pageCountOf does: an encrypted
// or malformed PDF should fall through to the ordinary upload and get the extractor's real error
// message, not be refused by a preview.
export interface PickedPdf {
  pages: number
  render: (pageNumber: number, canvas: HTMLCanvasElement, maxWidth: number) => Promise<void>
  destroy: () => void
}

export async function openPdf(file: File): Promise<PickedPdf | null> {
  try {
    const bytes = new Uint8Array(await file.arrayBuffer())
    const doc = await pdfjs.getDocument({ data: bytes }).promise
    if (doc.numPages < 1) {
      void doc.destroy()
      return null
    }
    return {
      pages: doc.numPages,
      render: async (pageNumber, canvas, maxWidth) => {
        const page = await doc.getPage(pageNumber)
        // Scale to the width the thumbnail is drawn at rather than rendering at 1.0 and letting
        // CSS shrink it: a 300-page textbook page at full scale is a megabyte of canvas per
        // thumbnail, and the reader is looking at a 150px-wide picture either way.
        const unscaled = page.getViewport({ scale: 1 })
        const viewport = page.getViewport({ scale: maxWidth / unscaled.width })
        const context = canvas.getContext('2d')
        if (!context) return
        canvas.width = Math.floor(viewport.width)
        canvas.height = Math.floor(viewport.height)
        await page.render({ canvas, canvasContext: context, viewport }).promise
        // pdf.js keeps the page's operator list cached on the proxy until it is told not to.
        page.cleanup()
      },
      destroy: () => {
        void doc.destroy()
      },
    }
  } catch {
    return null
  }
}

// The pre-upload estimate, in words.
//
// **Mirrors IngestionEstimate.java, and deliberately makes the weaker claim of the two.** The
// server can say "about 2 minutes" because by then the quality gate has counted the pages that
// need the vision model; nothing in the browser can, because that count is 0% of a typeset PDF and
// 100% of a scan and the difference is not visible in the page tree. So this states the part that
// is proportional to pages and names the per-page cost of the part that is not, rather than
// inventing a single number that would be wrong by a factor of ten on half the uploads.
const SECONDS_PER_PAGE = 0.25
const SECONDS_PER_VISION_PAGE = 4

export function estimateFor(pages: number): string {
  const seconds = Math.round(pages * SECONDS_PER_PAGE)
  const base =
    seconds < 60 ? `about ${Math.max(10, Math.round(seconds / 10) * 10)}s` : `about ${Math.round(seconds / 60)} min`
  return `${base} to read and index, plus about ${SECONDS_PER_VISION_PAGE}s for each page that needs the vision model`
}
