import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { useLocation } from 'react-router-dom'
import { documentsApi, guidesApi, videosApi } from './api'

// Phase 29.4 — saying when a long job has finished.
//
// **Ingest and video both run for minutes, and until now both were polled by the page that
// started them.** Navigate away and the poll stops; come back and the only way to find out what
// happened is to look. The work itself needed nothing: `DocumentStatusService` and
// `VideoJobStatusService` have owned every transition since Phase 4 and Phase 21. What was missing
// is that nobody was watching on the reader's behalf.
//
// So the watch moves up, out of the page and into the shell. A job registered here is followed
// until it reaches a terminal state no matter which page is open, and the answer arrives three
// ways, in descending order of how much it interrupts:
//
//   1. a badge on the rail link, which waits until you look;
//   2. a toast, if you are still in the app;
//   3. a browser notification, only with permission, only for a job you started, and only when
//      the tab is not the one you are looking at.
//
// The third is last for a reason. A notification for a tab in front of you is noise, and the
// permission is asked for at the moment a long job starts rather than on arrival — a prompt whose
// answer is "why are you asking me this" is a prompt that gets denied forever.

export type JobKind = 'document' | 'video' | 'guide'

export interface JobWatch {
  kind: JobKind
  courseId: string
  id: string
  // What to call it in the toast: a filename, or a video's topic.
  label: string
}

export interface FinishedJob extends JobWatch {
  key: string
  ok: boolean
  detail: string
}

// Four seconds, matching the two page-level polls this replaces. Long jobs, and a tighter loop
// buys nothing but requests.
const POLL_MS = 4000

// After this the watch is abandoned. A render that has not finished in forty minutes has not
// finished, and a poll that never stops is a background request loop for the rest of the session.
const GIVE_UP_MS = 40 * 60 * 1000

const TOAST_MS = 9000

interface JobWatchValue {
  watch: (job: JobWatch) => void
  finished: FinishedJob[]
  pending: number
}

const JobWatchContext = createContext<JobWatchValue>({
  watch: () => undefined,
  finished: [],
  pending: 0,
})

export function useJobWatch(): JobWatchValue {
  return useContext(JobWatchContext)
}

// How many finished-and-not-yet-looked-at jobs of one kind a course has, for the rail badge.
export function useJobBadge(courseId: string | undefined, kind: JobKind): number {
  const { finished } = useJobWatch()
  if (!courseId) return 0
  return finished.filter((job) => job.courseId === courseId && job.kind === kind).length
}

export function JobWatchProvider({ children }: { children: ReactNode }) {
  const [watching, setWatching] = useState<(JobWatch & { since: number })[]>([])
  const [finished, setFinished] = useState<FinishedJob[]>([])
  const [toasts, setToasts] = useState<FinishedJob[]>([])
  const location = useLocation()

  // The interval reads the live list through a ref so that registering a second job does not tear
  // down and rebuild the timer mid-poll.
  const live = useRef(watching)
  live.current = watching

  const watch = useCallback((job: JobWatch) => {
    askToNotify()
    setWatching((current) =>
      current.some((w) => w.kind === job.kind && w.id === job.id)
        ? current
        : [...current, { ...job, since: Date.now() }],
    )
  }, [])

  const settle = useCallback((job: JobWatch, ok: boolean, detail: string) => {
    const done: FinishedJob = { ...job, key: `${job.kind}:${job.id}`, ok, detail }
    setWatching((current) => current.filter((w) => !(w.kind === job.kind && w.id === job.id)))
    setFinished((current) => [...current.filter((f) => f.key !== done.key), done])
    setToasts((current) => [...current.filter((f) => f.key !== done.key), done])
    notify(done)
  }, [])

  useEffect(() => {
    if (watching.length === 0) return
    const timer = window.setInterval(() => {
      for (const job of live.current) {
        if (Date.now() - job.since > GIVE_UP_MS) {
          setWatching((current) => current.filter((w) => w !== job))
          continue
        }
        void poll(job).then((result) => {
          if (result) settle(job, result.ok, result.detail)
        })
      }
    }, POLL_MS)
    return () => window.clearInterval(timer)
  }, [watching.length, settle])

  // Arriving at the page a job belongs to is the same as having been told: the badge clears.
  useEffect(() => {
    setFinished((current) => current.filter((job) => location.pathname !== pageOf(job)))
  }, [location.pathname])

  // Toasts expire on their own. The badge does not — it is the part that waits.
  useEffect(() => {
    if (toasts.length === 0) return
    const timer = window.setTimeout(() => setToasts((current) => current.slice(1)), TOAST_MS)
    return () => window.clearTimeout(timer)
  }, [toasts])

  const value = useMemo(
    () => ({ watch, finished, pending: watching.length }),
    [watch, finished, watching.length],
  )

  return (
    <JobWatchContext.Provider value={value}>
      {children}
      <Toasts toasts={toasts} onDismiss={(key) => setToasts((c) => c.filter((t) => t.key !== key))} />
    </JobWatchContext.Provider>
  )
}

// One poll of one job. Returns null while it is still running, and null on a transient failure —
// the next tick asks again. A job that has been deleted answers 404, which `request` throws, and
// that is indistinguishable here from the network being down for a second; the give-up window is
// what stops either from being followed forever.
async function poll(job: JobWatch): Promise<{ ok: boolean; detail: string } | null> {
  try {
    if (job.kind === 'document') {
      // Named `doc`, not `document`: the global one is read a few lines below and a shadow here
      // would be a bug waiting for someone to move the code.
      const doc = await documentsApi.get(job.courseId, job.id)
      if (doc.status === 'READY') {
        return { ok: true, detail: 'Ready to be asked about.' }
      }
      if (doc.status === 'FAILED') {
        return { ok: false, detail: doc.errorMessage ?? 'Ingestion failed.' }
      }
      return null
    }
    if (job.kind === 'guide') {
      // Phase 22. REFUSED is reported as `ok: false` here for the same reason a refused render is:
      // this notification exists to say whether there is something to go and read, and there is
      // not. The page itself draws the distinction between "declined" and "broke".
      const guide = await guidesApi.get(job.courseId, job.id)
      if (guide.status === 'READY') {
        return {
          ok: true,
          detail:
            guide.sectionsWritten === guide.sectionsPlanned
              ? 'The guide is ready to read.'
              : `Ready — ${guide.sectionsPlanned - guide.sectionsWritten} of ` +
                `${guide.sectionsPlanned} sections were gaps in your materials.`,
        }
      }
      if (guide.status === 'FAILED') {
        return { ok: false, detail: guide.error ?? 'The guide did not finish.' }
      }
      if (guide.status === 'REFUSED') {
        return { ok: false, detail: 'Your materials do not cover that topic.' }
      }
      return null
    }
    const video = await videosApi.get(job.courseId, job.id)
    if (video.status === 'READY') {
      return { ok: true, detail: 'The video is ready to watch.' }
    }
    if (video.status === 'FAILED' || video.status === 'REFUSED') {
      return { ok: false, detail: video.stage ?? 'The render did not finish.' }
    }
    return null
  } catch {
    return null
  }
}

function pageOf(job: FinishedJob): string {
  if (job.kind === 'video') return `/courses/${job.courseId}/videos`
  if (job.kind === 'guide') return `/courses/${job.courseId}/guides`
  return `/courses/${job.courseId}`
}

// Asked at the moment a long job starts, which is the only moment the question makes sense. Once
// answered — either way — the browser never returns 'default' again, so this cannot nag.
function askToNotify() {
  if (typeof Notification === 'undefined' || Notification.permission !== 'default') return
  void Notification.requestPermission().catch(() => undefined)
}

function notify(job: FinishedJob) {
  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return
  // The toast has this covered if they are looking at the page.
  if (document.visibilityState === 'visible') return
  try {
    new Notification(job.ok ? `${job.label} is ready` : `${job.label} failed`, { body: job.detail })
  } catch {
    // Some browsers only allow notifications through a service worker. There is one signal left
    // and it is already on screen.
  }
}

function Toasts({ toasts, onDismiss }: { toasts: FinishedJob[]; onDismiss: (key: string) => void }) {
  if (toasts.length === 0) return null
  return (
    <div className="pointer-events-none fixed right-4 bottom-4 z-50 flex w-[min(22rem,calc(100vw-2rem))] flex-col gap-2">
      {toasts.map((job) => (
        <button
          key={job.key}
          type="button"
          onClick={() => onDismiss(job.key)}
          className="pointer-events-auto cursor-pointer rounded-card border border-line-strong bg-surface p-3 text-left shadow-card"
        >
          <p className="m-0 text-[13px] font-medium text-ink">
            {job.ok ? `${job.label} is ready` : `${job.label} failed`}
          </p>
          <p className="m-0 mt-0.5 text-[12px] leading-[1.45] text-ink-muted">{job.detail}</p>
        </button>
      ))}
    </div>
  )
}
