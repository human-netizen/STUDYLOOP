import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ApiError, coursesApi, guidesApi } from '../lib/api'
import type {
  Citation,
  CourseResponse,
  StudyGuide,
  StudyGuideLibrary,
  StudyGuideSection,
} from '../lib/types'
import { AppShell } from '../components/AppShell'
import { Markdown } from '../components/Markdown'
import { Mermaid } from '../components/Mermaid'
import { PdfViewer, pdfTargetOf, type PdfTarget } from '../components/PdfViewer'
import {
  Button,
  Empty,
  ErrorText,
  Eyebrow,
  Input,
  Loading,
  Meta,
  PageTitle,
  Panel,
  Pill,
  ProgressBar,
  SectionHead,
} from '../components/ui'
import { useJobWatch } from '../lib/jobs'
import { cx } from '../lib/style'

// Phase 22 — asking for a study guide, watching it being written, and printing it.
//
// **The gaps are the part of this page worth defending.** A section the course cannot support is
// drawn in its own place in the reading order, named, and marked as not covered — not hidden, and
// not filled in from the model's general knowledge. Every other study-guide generator produces a
// document with no holes in it, which is a claim about the model rather than about the course. The
// holes here are the honest output, and they are where a student goes next: the forum, the
// instructor, or a search for what is missing.
//
// **The PDF export is `window.print()` and nothing else** (22.3). The styled HTML already exists —
// it is this page — and `@media print` in index.css re-points every design token for paper, which
// Phase 29.1 built and this inherits whole. A server-side renderer would re-implement the styling
// in a second language and produce a PDF that looks like a different product, which is exactly
// what ZenLearn's `xhtml2pdf` path does. Zero new backend dependencies, and what you print is what
// you were reading.

// A guide is tens of seconds of model calls. Two seconds between polls, matching the video page —
// what is being watched is "4 of 6 sections", which changes at the speed of a completion.
const POLL_MS = 2000

export function CourseGuidesPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()

  const [course, setCourse] = useState<CourseResponse | null>(null)
  const [library, setLibrary] = useState<StudyGuideLibrary | null>(null)
  const [selected, setSelected] = useState<StudyGuide | null>(null)
  const [topic, setTopic] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [citation, setCitation] = useState<PdfTarget | null>(null)
  const { watch } = useJobWatch()

  useEffect(() => {
    let active = true
    Promise.all([coursesApi.get(id), guidesApi.library(id)])
      .then(([courseData, libraryData]) => {
        if (!active) return
        setCourse(courseData)
        setLibrary(libraryData)
        // The newest guide still being written, or failing that the newest one at all. Somebody
        // who reloads mid-generation wants to be back where they were.
        const running = libraryData.guides.find((guide) => !isTerminal(guide))
        const open = running ?? libraryData.guides[0]
        if (open) void guidesApi.get(id, open.id).then((full) => active && setSelected(full))
      })
      .catch((err) => {
        if (active) setError(err instanceof ApiError ? err.message : 'Failed to load study guides.')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [id])

  const refreshLibrary = useCallback(() => {
    guidesApi
      .library(id)
      .then(setLibrary)
      .catch(() => undefined)
  }, [id])

  // Polling stops on its own: a terminal guide is never polled again, so an idle tab on a finished
  // guide makes no requests at all.
  useEffect(() => {
    if (!selected || isTerminal(selected)) return
    const timer = window.setInterval(() => {
      guidesApi
        .get(id, selected.id)
        .then((guide) => {
          setSelected(guide)
          if (isTerminal(guide)) refreshLibrary()
        })
        .catch(() => undefined)
    }, POLL_MS)
    return () => window.clearInterval(timer)
  }, [id, selected, refreshLibrary])

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    const asked = topic.trim()
    if (!asked || submitting) return
    setSubmitting(true)
    setError(null)
    try {
      const guide = await guidesApi.request(id, asked)
      setTopic('')
      setSelected(guide)
      refreshLibrary()
      // Phase 29.4 — six completions is a minute, and nobody sits on this page for it.
      watch({ kind: 'guide', courseId: id, id: guide.id, label: asked })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not start that guide.')
    } finally {
      setSubmitting(false)
    }
  }

  async function remove(guideId: string) {
    try {
      await guidesApi.remove(id, guideId)
      if (selected?.id === guideId) setSelected(null)
      refreshLibrary()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not delete that guide.')
    }
  }

  return (
    <AppShell courseName={course?.name}>
      <div className="print:hidden">
        <PageTitle
          eyebrow={course?.name ?? 'Course'}
          title="Study guide"
          sub="A guide written from this course's own materials, section by section — and told plainly where they run out."
        />
      </div>

      {loading && <Loading />}
      {error && <ErrorText>{error}</ErrorText>}

      {!loading && library && !library.available && <NotAvailable />}

      {!loading && library?.available && (
        <>
          <Panel className="mb-5 print:hidden">
            <form onSubmit={(event) => void submit(event)} className="flex flex-wrap gap-2">
              <Input
                value={topic}
                onChange={(event) => setTopic(event.target.value)}
                placeholder="What should the guide cover?"
                maxLength={300}
                className="min-w-[16rem] flex-1"
              />
              <Button type="submit" variant="primary" disabled={submitting || !topic.trim()}>
                {submitting ? 'Starting…' : 'Write a guide'}
              </Button>
            </form>
            <Meta className="mt-2">
              One planning call plus one per section it can support, against your usual AI
              allowance. A section your materials don't cover is listed as a gap and costs nothing.
            </Meta>
          </Panel>

          <div className="grid gap-5 lg:grid-cols-[18rem_1fr]">
            <div className="print:hidden">
              <SectionHead index="01 · Yours" title="Your guides" />
              {library.guides.length === 0 && <Empty>Nothing yet.</Empty>}
              <div className="flex flex-col gap-1.5">
                {library.guides.map((guide) => (
                  <GuideRow
                    key={guide.id}
                    guide={guide}
                    active={selected?.id === guide.id}
                    onOpen={() => void guidesApi.get(id, guide.id).then(setSelected)}
                  />
                ))}
              </div>
            </div>

            <div>
              {selected ? (
                <GuideDetail
                  guide={selected}
                  onCite={(cite) => setCitation(pdfTargetOf(cite))}
                  onDelete={() => void remove(selected.id)}
                  onAskInChat={() => navigate(`/courses/${id}/chat`)}
                />
              ) : (
                <Empty>Ask for a guide and it will appear here.</Empty>
              )}
            </div>
          </div>
        </>
      )}

      {citation && <PdfViewer courseId={id} target={citation} onClose={() => setCitation(null)} />}
    </AppShell>
  )
}

function isTerminal(guide: StudyGuide) {
  return guide.status === 'READY' || guide.status === 'FAILED' || guide.status === 'REFUSED'
}

// ── the list ────────────────────────────────────────────────────────────────────────────────

function GuideRow({
  guide,
  active,
  onOpen,
}: {
  guide: StudyGuide
  active: boolean
  onOpen: () => void
}) {
  return (
    <button
      type="button"
      onClick={onOpen}
      className={cx(
        'cursor-pointer rounded-lg border p-2.5 text-left transition duration-150',
        active
          ? 'border-accent/40 bg-surface-2'
          : 'border-line-soft bg-surface hover:border-line hover:bg-surface-2',
      )}
    >
      <div className="mb-1 flex items-center gap-2">
        <StatusPill status={guide.status} />
        {guide.status === 'READY' && (
          <Meta>
            {guide.sectionsWritten} of {guide.sectionsPlanned} written
          </Meta>
        )}
      </div>
      <div className="text-[13px] leading-snug text-ink">{guide.topic}</div>
    </button>
  )
}

function StatusPill({ status }: { status: StudyGuide['status'] }) {
  const tone =
    status === 'READY' ? 'ok' : status === 'FAILED' ? 'bad' : status === 'REFUSED' ? 'warn' : 'neutral'
  return <Pill tone={tone}>{status.toLowerCase()}</Pill>
}

// ── one guide ───────────────────────────────────────────────────────────────────────────────

function GuideDetail({
  guide,
  onCite,
  onDelete,
  onAskInChat,
}: {
  guide: StudyGuide
  onCite: (citation: Citation) => void
  onDelete: () => void
  onAskInChat: () => void
}) {
  const gaps = guide.sections.filter((section) => !section.covered).length

  return (
    <Panel>
      <div className="mb-3 flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="print:hidden">
            <Eyebrow>{guide.language === 'BANGLA' ? 'Written in Bangla' : 'Written in English'}</Eyebrow>
          </div>
          <h2 className="mt-1 mb-0 text-[15px] leading-snug text-ink print:text-[18px]">
            {guide.topic}
          </h2>
        </div>
        {isTerminal(guide) && (
          <div className="flex shrink-0 gap-1.5 print:hidden">
            {guide.status === 'READY' && (
              // 22.3's PDF export. The browser's own dialog, over the page you are already
              // reading — see the note at the top of this file for why there is no renderer.
              <Button variant="ghost" size="sm" onClick={() => window.print()}>
                Print / PDF
              </Button>
            )}
            <Button variant="quiet" size="sm" onClick={onDelete}>
              Delete
            </Button>
          </div>
        )}
      </div>

      {!isTerminal(guide) && <Progress guide={guide} />}

      {guide.status === 'REFUSED' && (
        <Refused topic={guide.topic} onAskInChat={onAskInChat} />
      )}

      {guide.status === 'FAILED' && guide.error && (
        <ErrorText className="mb-3">{guide.error}</ErrorText>
      )}

      {guide.status === 'READY' && gaps > 0 && (
        // Said once at the top as well as in place, because it is the guide's headline finding:
        // a student deciding whether to revise from this needs to know before section four.
        <Meta className="mb-4 block">
          {gaps === 1
            ? '1 section is not covered by this course’s materials and was left as a gap.'
            : `${gaps} sections are not covered by this course’s materials and were left as gaps.`}
        </Meta>
      )}

      {guide.sections.map((section) => (
        <GuideSectionView key={section.position} section={section} onCite={onCite} />
      ))}

      {guide.status === 'READY' && (
        <Meta className="mt-6 block border-t border-line-soft pt-3">
          {guide.sectionsWritten} of {guide.sectionsPlanned} sections written · {guide.modelCalls}{' '}
          model {guide.modelCalls === 1 ? 'call' : 'calls'} · every claim above is cited to a page
          of this course's materials.
        </Meta>
      )}
    </Panel>
  )
}

function GuideSectionView({
  section,
  onCite,
}: {
  section: StudyGuideSection
  onCite: (citation: Citation) => void
}) {
  return (
    <section className="mt-5 first:mt-0">
      <h3 className="mb-1.5 text-[14px] font-semibold text-ink">
        {section.position}. {section.heading}
      </h3>

      {section.covered ? (
        <>
          <Markdown text={section.body ?? ''} citations={section.citations} onCite={onCite} />
          {section.diagram && <Mermaid source={section.diagram} />}
        </>
      ) : (
        <Gap />
      )}
    </section>
  )
}

// The gap, rendered as itself.
//
// Not an error state and not a spinner: this section was planned because the topic needs it, and
// the course's materials do not cover it. That sentence is the useful output — it is the one thing
// a generated study guide can tell a student that a textbook cannot.
function Gap() {
  return (
    <div className="rounded-ctl border border-dashed border-line px-3 py-2.5">
      <Meta>
        Not covered by this course's materials. Nothing was written here rather than filling it in
        from outside the course — ask the class in the forum, or your instructor.
      </Meta>
    </div>
  )
}

function Progress({ guide }: { guide: StudyGuide }) {
  const planned = guide.sectionsPlanned
  const label =
    guide.status === 'QUEUED'
      ? 'Queued'
      : guide.status === 'PLANNING'
        ? 'Reading what this course has on the topic'
        : `Writing section ${Math.min(guide.sections.length + 1, planned || 1)} of ${planned || '…'}`
  // Planning is the whole first step and it is roughly a sixth of the work, so the bar starts
  // there rather than at zero — a bar that sits at 0% while a model call is in flight reads as a
  // job that has not started.
  const percent = planned > 0 ? 15 + (guide.sections.length / planned) * 85 : 8
  return <ProgressBar className="mb-4" percent={percent} label={label} />
}

// The confidence gate's decision, offered with the two ways out a refused chat answer gets
// (Phase 20.2) rather than as an error. Nothing was spent past one embedding.
function Refused({ topic, onAskInChat }: { topic: string; onAskInChat: () => void }) {
  return (
    <div className="mb-3 rounded-ctl border border-line bg-surface-2 p-3">
      <p className="my-0 text-[13px] text-ink-2">
        This course's materials don't cover “{topic}” well enough to write a guide from. Nothing was
        generated, because a guide written from outside the course would look exactly like one
        written from inside it.
      </p>
      <Button variant="ghost" size="sm" className="mt-2.5" onClick={onAskInChat}>
        Ask in chat instead
      </Button>
    </div>
  )
}

function NotAvailable() {
  return (
    <Panel>
      <Eyebrow>Not configured</Eyebrow>
      <p className="mt-1.5 mb-0 text-[13px] text-ink-2">
        Study guides need an AI provider, the same one chat and summaries use. This installation
        has none configured, so there is nothing to write with.
      </p>
    </Panel>
  )
}
