import { useEffect, useId, useRef, useState } from 'react'
import { cx } from '../lib/style'

// Phase 22.2 — the diagram half of a study guide.
//
// **Mermaid rather than a generated image, and the reason is in PLAN.md's "deliberately not
// building" table.** An AI illustration costs money per picture and produces something decorative
// with no informational content — for a diagram of a B-tree it is actively misleading, because a
// picture is read as a fact. Mermaid is text the model derived from the sources, so it can be
// cited, diffed, printed, and — the part that matters here — checked before it is drawn.
//
// **Loaded on demand, never in the bundle.** Mermaid is about three megabytes with its parser;
// paying that on every page load for a feature only this page uses would be the whole app made
// slower by a diagram most sections do not have. The dynamic import means it arrives the first
// time a guide with a diagram is opened and is cached from then on.
//
// **`securityLevel: 'strict'` and `htmlLabels: false`, and both matter.** This SVG is generated
// from model output, and model output is partly the student's input — a topic typed into a box
// reaches the prompt. Strict sandboxes the render and disables `click` directives; no HTML labels
// means a label is text rather than markup. The server drops diagrams containing `click`, `href`
// or a script tag before they are ever stored (see StudyGuidePlanner), so a diagram has to pass
// both. That is the same posture 11.2 took by refusing `rehype-raw` rather than sanitizing it.

let mermaidReady: Promise<typeof import('mermaid').default> | null = null

function loadMermaid() {
  if (!mermaidReady) {
    mermaidReady = import('mermaid').then(({ default: mermaid }) => {
      mermaid.initialize({
        startOnLoad: false,
        securityLevel: 'strict',
        htmlLabels: false,
        theme: 'base',
        fontFamily: 'inherit',
        // A near-monochrome palette, chosen so the print rule below can simply invert it. Colour
        // in a generated diagram would have to mean something, and nothing here decides what.
        themeVariables: {
          background: 'transparent',
          primaryColor: '#1c2227',
          primaryTextColor: '#e7edf2',
          primaryBorderColor: '#6b7885',
          secondaryColor: '#161b1f',
          tertiaryColor: '#0f1215',
          lineColor: '#9aa8b4',
          textColor: '#e7edf2',
          mainBkg: '#1c2227',
          nodeBorder: '#6b7885',
        },
      })
      return mermaid
    })
  }
  return mermaidReady
}

interface MermaidProps {
  source: string
  className?: string
}

export function Mermaid({ source, className }: MermaidProps) {
  const [svg, setSvg] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  // Mermaid needs a DOM id per render; useId gives a stable one per component instance.
  const domId = `mermaid-${useId().replace(/:/g, '')}`
  // A render that resolves after the source has changed again must not win.
  const latest = useRef(source)

  useEffect(() => {
    latest.current = source
    let cancelled = false
    setFailed(false)
    loadMermaid()
      .then((mermaid) => mermaid.render(domId, source))
      .then(({ svg: rendered }) => {
        if (!cancelled && latest.current === source) {
          setSvg(rendered)
        }
      })
      .catch(() => {
        if (!cancelled) {
          setSvg(null)
          setFailed(true)
        }
      })
    return () => {
      cancelled = true
    }
  }, [source, domId])

  // **A diagram that will not parse shows its source rather than nothing.** Dropping it silently
  // would mean nobody ever learns the model is writing invalid Mermaid — the same argument Phase
  // 21 makes about counting a scene that fell back to a slide instead of quietly drawing one.
  if (failed) {
    return (
      <figure className={cx('my-3', className)}>
        <figcaption className="mb-1 text-[11px] text-ink-muted">
          This diagram could not be drawn. Its source, as written:
        </figcaption>
        <pre className="overflow-x-auto rounded-ctl border border-line bg-ground-2 p-3 font-mono text-[12px]">
          {source}
        </pre>
      </figure>
    )
  }

  if (!svg) {
    return <div className={cx('my-3 h-16 animate-pulse rounded-ctl bg-surface-2', className)} />
  }

  return (
    <figure
      className={cx('mermaid my-3 overflow-x-auto rounded-ctl border border-line bg-ground-2 p-3', className)}
      // The SVG is mermaid's own output, rendered in strict mode from source the server already
      // filtered. This is the one place in the app that sets markup directly, and the two checks
      // in front of it are what make that defensible.
      dangerouslySetInnerHTML={{ __html: svg }}
    />
  )
}
