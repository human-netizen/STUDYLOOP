import { useCallback, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import type { Citation } from '../lib/types'

// Phase 29.5 — the passage behind a citation, without opening the document.
//
// **The drawer is the most expensive interaction in the product.** Clicking a source downloads the
// whole file to render one page; on a 300-page book that is tens of megabytes to answer a question
// the reader could have settled by reading two lines. `Citation.snippet` has been on the wire since
// Phase 5 and rendered in search results ever since, and chat has never shown it.
//
// So: hover or keyboard-focus a marker and the passage appears. The click still opens the page,
// because "show me where this is" is a different question from "is this the right passage".
//
// **Positioned through a portal, deliberately.** The transcript is an `overflow-y-auto` column, and
// a card absolutely positioned inside it is clipped by that scroll box — which is invisible in
// development, where the answer being tested is always in the middle of the viewport, and obvious
// to a reader hovering the top line. Fixed coordinates measured from the trigger escape the
// clipping entirely.
const CARD_WIDTH = 320
const GAP = 8

export function CitationPreview({ citation, children }: { citation: Citation; children: ReactNode }) {
  const anchor = useRef<HTMLSpanElement>(null)
  const [box, setBox] = useState<{ top: number; left: number; above: boolean } | null>(null)

  const show = useCallback(() => {
    const rect = anchor.current?.getBoundingClientRect()
    if (!rect) return
    // Above unless there is no room, which is the normal case for a citation on the first line of
    // an answer.
    const above = rect.top > 180
    setBox({
      top: above ? rect.top - GAP : rect.bottom + GAP,
      left: Math.max(GAP, Math.min(rect.left, window.innerWidth - CARD_WIDTH - GAP)),
      above,
    })
  }, [])

  const hide = useCallback(() => setBox(null), [])

  const snippet = citation.snippet?.trim() ?? ''
  if (snippet.length === 0) {
    return <>{children}</>
  }

  return (
    <span
      ref={anchor}
      className="inline"
      onMouseEnter={show}
      onMouseLeave={hide}
      onFocus={show}
      onBlur={hide}
    >
      {children}
      {box &&
        createPortal(
          <div
            role="tooltip"
            style={{
              position: 'fixed',
              top: box.top,
              left: box.left,
              width: CARD_WIDTH,
              transform: box.above ? 'translateY(-100%)' : undefined,
            }}
            // Not interactive: the pointer must be able to cross it without the card fighting the
            // mouseleave that closes it.
            className="pointer-events-none z-50 rounded-ctl border border-line-strong bg-surface p-3 shadow-card"
          >
            <p className="tnum m-0 mb-1.5 font-mono text-[10.5px] text-ink-muted">
              [{citation.index}] {citation.filename}
              {citation.pageNumber != null && ` · p.${citation.pageNumber}`}
              {citation.visual && ' · figure'}
            </p>
            <p className="m-0 text-[12.5px] leading-[1.5] text-ink">{snippet}</p>
          </div>,
          document.body,
        )}
    </span>
  )
}
