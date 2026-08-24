import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ApiError, coursesApi, forumApi, videosApi } from '../lib/api'
import type { Citation, CourseResponse, VideoJob, VideoLibrary, VideoScene } from '../lib/types'
import { AppShell } from '../components/AppShell'
import { PdfViewer } from '../components/PdfViewer'
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
  SectionHead,
} from '../components/ui'
import { cx } from '../lib/style'

// Phase 21.5 — asking for a video, watching it being made, and watching it.
//
// **Nothing on this page exists when the renderer does not.** `library.enabled` is false on an
// installation without the sidecar, and then there is no form, no button and no list — one
// explanatory panel instead. A button that fails is worse than an absent feature, and this is the
// same rule the vision key follows in Phase 15.
//
// The three states a job can end in are three different screens, deliberately: READY is a player
// with a source rail, FAILED is a reason, and REFUSED is neither — it is the confidence gate
// saying the corpus cannot support the topic, offered with the same way out a refused chat answer
// gets.

// While a job is running the page polls it. Two seconds, matching the upload page: a render takes
// minutes, and the thing being watched is the stage sentence rather than a percentage.
const POLL_MS = 2000

export function CourseVideosPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()

  const [course, setCourse] = useState<CourseResponse | null>(null)
  const [library, setLibrary] = useState<VideoLibrary | null>(null)
  const [selected, setSelected] = useState<VideoJob | null>(null)
  const [topic, setTopic] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [citation, setCitation] = useState<Citation | null>(null)

  useEffect(() => {
    let active = true
    Promise.all([coursesApi.get(id), videosApi.library(id)])
      .then(([courseData, libraryData]) => {
        if (!active) return
        setCourse(courseData)
        setLibrary(libraryData)
        // Open the newest job that is still running, or the newest one at all. A student who
        // reloads while a render is going wants to be back where they were.
        const running = libraryData.jobs.find((job) => !isTerminal(job))
        const open = running ?? libraryData.jobs[0]
        if (open) void videosApi.get(id, open.id).then((full) => active && setSelected(full))
      })
      .catch((err) => {
        if (active) setError(err instanceof ApiError ? err.message : 'Failed to load videos.')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [id])

  const refreshLibrary = useCallback(() => {
    videosApi
      .library(id)
      .then(setLibrary)
      .catch(() => undefined)
  }, [id])

  // Polling, and it stops on its own. A terminal job is never polled again, so an idle tab on a
  // finished video makes no requests at all.
  useEffect(() => {
    if (!selected || isTerminal(selected)) return
    const timer = window.setInterval(() => {
      videosApi
        .get(id, selected.id)
        .then((job) => {
          setSelected(job)
          if (isTerminal(job)) refreshLibrary()
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
      const job = await videosApi.request(id, asked)
      setTopic('')
      setSelected(job)
      refreshLibrary()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not start the render.')
    } finally {
      setSubmitting(false)
    }
  }

  async function remove(jobId: string) {
    try {
      await videosApi.remove(id, jobId)
      if (selected?.id === jobId) setSelected(null)
      refreshLibrary()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not delete that video.')
    }
  }

  const capReached = library != null && library.usedToday >= library.dailyCap

  return (
    <AppShell courseName={course?.name}>
      <PageTitle
        eyebrow={course?.name ?? 'Course'}
        title="Video"
        sub="A narrated explanation, built from this course's materials and cited back to them."
      />

      {loading && <Loading />}
      {error && <ErrorText>{error}</ErrorText>}

      {!loading && library && !library.enabled && <NotAvailable />}

      {!loading && library?.enabled && (
        <>
          {!library.workerReachable && <RendererDown />}

          <Panel className="mb-5">
            <form onSubmit={(event) => void submit(event)} className="flex flex-wrap gap-2">
              <Input
                value={topic}
                onChange={(event) => setTopic(event.target.value)}
                placeholder="What should the video explain?"
                maxLength={300}
                className="min-w-[16rem] flex-1"
                disabled={!library.workerReachable || capReached}
              />
              <Button
                type="submit"
                variant="primary"
                disabled={
                  submitting || !topic.trim() || !library.workerReachable || capReached
                }
              >
                {submitting ? 'Starting…' : 'Make a video'}
              </Button>
            </form>
            <Meta className="mt-2">
              {capReached
                ? `You have used all ${library.dailyCap} of today's videos. Refusals do not count.`
                : `${library.dailyCap - library.usedToday} of ${library.dailyCap} left today. ` +
                  'Two to three minutes of video, and a few minutes of rendering, per request.'}
            </Meta>
          </Panel>

          <div className="grid gap-5 lg:grid-cols-[18rem_1fr]">
            <div>
              <SectionHead index="01 · Yours" title="Your videos" />
              {library.jobs.length === 0 && <Empty>Nothing yet.</Empty>}
              <CardStack>
                {library.jobs.map((job) => (
                  <JobRow
                    key={job.id}
                    job={job}
                    active={selected?.id === job.id}
                    onOpen={() => void videosApi.get(id, job.id).then(setSelected)}
                  />
                ))}
              </CardStack>
            </div>

            <div>
              {selected ? (
                <JobDetail
                  courseId={id}
                  job={selected}
                  onCite={setCitation}
                  onDelete={() => void remove(selected.id)}
                  onAskInChat={() => navigate(`/courses/${id}/chat`)}
                />
              ) : (
                <Empty>Pick a video, or ask for one.</Empty>
              )}
            </div>
          </div>
        </>
      )}

      {citation && (
        <PdfViewer
          key={citation.documentId}
          courseId={id}
          target={citation}
          onClose={() => setCitation(null)}
        />
      )}
    </AppShell>
  )
}

// A gapped stack rather than ui's bordered Rows: each of these is a card with its own status
// and its own selected state, and hairline dividers would read as one list of equals.
function CardStack({ children }: { children: React.ReactNode }) {
  return <div className="flex flex-col gap-1.5">{children}</div>
}

function isTerminal(job: VideoJob) {
  return job.status === 'READY' || job.status === 'FAILED' || job.status === 'REFUSED'
}

// ── the job list ────────────────────────────────────────────────────────────────────────────

function JobRow({
  job,
  active,
  onOpen,
}: {
  job: VideoJob
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
        <StatusPill status={job.status} />
        {job.durationSeconds != null && <Meta>{formatDuration(job.durationSeconds)}</Meta>}
      </div>
      <div className="text-[13px] leading-snug text-ink">{job.topic}</div>
      {!isTerminal(job) && job.stage && <Meta className="mt-1">{job.stage}</Meta>}
    </button>
  )
}

function StatusPill({ status }: { status: VideoJob['status'] }) {
  const tone =
    status === 'READY' ? 'ok' : status === 'FAILED' ? 'bad' : status === 'REFUSED' ? 'warn' : 'neutral'
  return <Pill tone={tone}>{status.toLowerCase()}</Pill>
}

// ── one job ─────────────────────────────────────────────────────────────────────────────────

function JobDetail({
  courseId,
  job,
  onCite,
  onDelete,
  onAskInChat,
}: {
  courseId: string
  job: VideoJob
  onCite: (citation: Citation) => void
  onDelete: () => void
  onAskInChat: () => void
}) {
  return (
    <Panel>
      <div className="mb-3 flex flex-wrap items-start justify-between gap-2">
        <div>
          <Eyebrow>{job.language === 'BANGLA' ? 'Narrated in Bangla' : 'Narrated in English'}</Eyebrow>
          <h2 className="mt-1 mb-0 text-[15px] leading-snug text-ink">{job.topic}</h2>
        </div>
        {isTerminal(job) && (
          <Button variant="quiet" size="sm" onClick={onDelete}>
            Delete
          </Button>
        )}
      </div>

      {!isTerminal(job) && <Progress job={job} />}
      {job.status === 'REFUSED' && (
        <Refused courseId={courseId} topic={job.topic} reason={job.error} onAskInChat={onAskInChat} />
      )}
      {job.status === 'FAILED' && (
        <ErrorText>{job.error ?? 'The render failed and gave no reason.'}</ErrorText>
      )}
      {job.status === 'READY' && <Player courseId={courseId} job={job} />}

      {job.scenes.length > 0 && (
        <>
          <FallbackSummary job={job} />
          <div className="mt-3 flex flex-col gap-2">
            {job.scenes.map((scene) => (
              <SceneRow key={scene.index} scene={scene} onCite={onCite} />
            ))}
          </div>
        </>
      )}
    </Panel>
  )
}

function Progress({ job }: { job: VideoJob }) {
  return (
    <div className="mb-3 rounded-lg border border-line-soft bg-surface-2 p-3">
      <div className="text-[13px] text-ink">{job.stage ?? 'Queued behind another render.'}</div>
      <Meta className="mt-1">
        {job.status === 'QUEUED'
          ? 'One video is rendered at a time, so this one starts when the machine is free.'
          : 'Rendering happens on this machine, so it takes a few minutes. You can leave this page.'}
      </Meta>
    </div>
  )
}

// The refusal, and the two ways out of it.
//
// The first is Phase 20.2's, unchanged: escalate to the class, where an accepted answer becomes
// course material and the next person who asks gets an answer instead of this. The second is not
// the general-knowledge button itself but a door to it — that path needs a conversation to write
// the ungrounded answer into, and a video request has none. Inventing a conversation here to hold
// one message would put a transcript in the student's chat history that they never typed.
function Refused({
  courseId,
  topic,
  reason,
  onAskInChat,
}: {
  courseId: string
  topic: string
  reason: string | null
  onAskInChat: () => void
}) {
  const [state, setState] = useState<'idle' | 'posting' | 'error'>('idle')
  const navigate = useNavigate()

  async function escalate() {
    setState('posting')
    try {
      const thread = await forumApi.open(courseId, { title: topic })
      navigate(`/courses/${courseId}/forum/${thread.id}`)
    } catch {
      setState('error')
    }
  }

  return (
    <div className="rounded-lg border border-warn/30 bg-warn-bg p-3">
      <p className="mt-0 mb-2 text-[13px] leading-relaxed text-ink">
        {reason ?? 'There is not enough in this course to build a video about that.'}
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <Button
          variant="primary"
          size="sm"
          onClick={() => void escalate()}
          disabled={state === 'posting'}
        >
          {state === 'posting' ? 'Opening…' : 'Ask the class'}
        </Button>
        <Button variant="quiet" onClick={onAskInChat}>
          Ask in chat instead
        </Button>
      </div>
      <Meta className="mt-2">
        {state === 'error'
          ? 'That did not work — try again.'
          : 'Nothing was rendered and nothing was spent: the check that refused this runs before the renderer is contacted.'}
      </Meta>
    </div>
  )
}

// **The fallback, stated.** This line is the answer to the objection that sank this feature the
// first time it was considered: a pipeline that falls back to static slides and says nothing is
// indistinguishable from one that animated everything.
function FallbackSummary({ job }: { job: VideoJob }) {
  if (job.scenesTotal === 0) return null
  const fell = job.scenesFallback
  return (
    <Meta className="mt-4 block">
      {job.scenesTotal} scenes · {job.scenesAnimated} animated
      {fell > 0 && ` · ${fell} rendered as static slides`}
      {fell > 0 && ' — the reason for each is under the scene.'}
    </Meta>
  )
}

function SceneRow({ scene, onCite }: { scene: VideoScene; onCite: (c: Citation) => void }) {
  return (
    <div className="rounded-lg border border-line-soft bg-surface p-2.5">
      <div className="mb-1 flex items-center gap-2">
        <span className="tnum font-mono text-[11px] text-ink-muted">
          {String(scene.index).padStart(2, '0')}
        </span>
        <span className="text-[13px] text-ink">{scene.title}</span>
        <Pill tone={scene.renderedAs === 'ANIMATED' ? 'ok' : 'neutral'}>
          {scene.renderedAs === 'ANIMATED' ? 'animated' : 'slide'}
        </Pill>
        {scene.durationSeconds != null && <Meta>{formatDuration(scene.durationSeconds)}</Meta>}
      </div>
      <p className="mt-0 mb-1.5 text-[12.5px] leading-relaxed text-ink-2">{scene.narration}</p>
      {scene.fallbackReason && (
        <Meta className="mb-1.5 block font-mono text-[11px]">{scene.fallbackReason}</Meta>
      )}
      {scene.citations.map((cite) => (
        <SourceLink key={cite.chunkId} citation={cite} onCite={onCite} />
      ))}
    </div>
  )
}

// The same link chat's citations use, because it is the same promise: this claim came from that
// page, and here is the page.
function SourceLink({ citation, onCite }: { citation: Citation; onCite: (c: Citation) => void }) {
  if (citation.documentSource === 'FORUM') {
    return (
      <span className="tnum block font-mono text-[11.5px] text-ink-muted">
        [{citation.index}] {citation.filename} · answered by the class
      </span>
    )
  }
  return (
    <button
      type="button"
      onClick={() => onCite(citation)}
      className="tnum block cursor-pointer border-0 bg-transparent p-0 text-left font-mono text-[11.5px] text-ink-muted underline decoration-transparent underline-offset-2 transition duration-150 hover:text-ink hover:decoration-accent"
    >
      [{citation.index}] {citation.filename}
      {citation.pageNumber != null && ` · p.${citation.pageNumber}`}
      {citation.visual && ' · figure'}
    </button>
  )
}

// ── the player ──────────────────────────────────────────────────────────────────────────────

// The bytes are fetched with the bearer token and played from an object URL.
//
// A plain `<video src="/api/...">` would be an unauthenticated request — the browser does not
// attach the token to a media element's own fetch — and these bytes can be grounded on the
// requester's private notes. The same reasoning made the PDF viewer fetch its file rather than
// link it, in Phase 6.2.
function Player({ courseId, job }: { courseId: string; job: VideoJob }) {
  const [videoUrl, setVideoUrl] = useState<string | null>(null)
  const [captionUrl, setCaptionUrl] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const urls = useRef<string[]>([])

  useEffect(() => {
    let active = true
    urls.current.forEach(URL.revokeObjectURL)
    urls.current = []

    videosApi
      .file(courseId, job.id)
      .then((blob) => {
        if (!active) return
        const url = URL.createObjectURL(blob)
        urls.current.push(url)
        setVideoUrl(url)
      })
      .catch(() => active && setError('The video could not be loaded.'))

    if (job.hasCaptions) {
      videosApi
        .captions(courseId, job.id)
        .then((blob) => {
          if (!active) return
          const url = URL.createObjectURL(blob)
          urls.current.push(url)
          setCaptionUrl(url)
        })
        // A missing caption track is a video without subtitles, not a broken video.
        .catch(() => undefined)
    }

    return () => {
      active = false
      urls.current.forEach(URL.revokeObjectURL)
      urls.current = []
    }
  }, [courseId, job.id, job.hasCaptions])

  if (error) return <ErrorText>{error}</ErrorText>
  if (!videoUrl) return <Loading>Loading the video</Loading>

  return (
    <video
      controls
      src={videoUrl}
      className="w-full rounded-lg border border-line-soft bg-ground"
      crossOrigin="anonymous"
    >
      {captionUrl && (
        <track kind="captions" srcLang="en" label="Narration" src={captionUrl} default />
      )}
    </video>
  )
}

// ── the two absent states ───────────────────────────────────────────────────────────────────

function NotAvailable() {
  return (
    <Panel>
      <p className="mt-0 mb-0 text-[13px] leading-relaxed text-ink-2">
        This installation does not generate videos. Rendering needs a separate service that carries
        an animation engine, a speech engine and ffmpeg — deliberately kept out of the application
        itself, so nothing here depends on it being installed.
      </p>
    </Panel>
  )
}

function RendererDown() {
  return (
    <Panel className="mb-5">
      <p className="mt-0 mb-0 text-[13px] leading-relaxed text-ink-2">
        The renderer is not running, so nothing can be made right now. Everything else in the course
        works normally — this is one optional service being down, not the application.
      </p>
    </Panel>
  )
}

function formatDuration(seconds: number) {
  const whole = Math.round(seconds)
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`
}
