import { useEffect, useRef, useState, type ReactNode } from 'react'
import { API_ORIGIN } from '../lib/api'
import {
  isProbablyAsleep,
  lastWakeSeconds,
  onProbablyAsleep,
  probeHealth,
  rememberWakeSeconds,
  type Probe,
} from '../lib/wake'
import { Button, Eyebrow } from './ui'
import { cx } from '../lib/style'

// Holds the app back until the API answers its health check, and says what is happening while it
// does not. On a warm server the check returns in one round trip and nothing is ever drawn; the
// screen appears only once a check has gone unanswered for SHOW_AFTER_MS.
//
// It sits above AuthProvider on purpose. AuthProvider's first act is /auth/me, and a request that
// reaches a sleeping Render instance can fail outright — so the session check waits here until the
// server is running instead of racing its boot.
//
// After the app has loaded, the same check runs again when a request is about to go to a server
// that has been silent for 14 minutes, or when the tab comes back after that long. The app stays
// mounted underneath and the screen is drawn over it.

const HEALTH_URL = `${API_ORIGIN}/actuator/health`
const SHOW_AFTER_MS = 1_000
const ATTEMPT_TIMEOUT_MS = 10_000
const ATTEMPT_EVERY_MS = 3_000
// Used for the progress bar until one wake has been measured on this browser.
const DEFAULT_WAKE_SECONDS = 75
// Past this the delay is no longer a cold start: a deploy in progress, a crash loop, or a bad URL.
const TOO_LONG_SECONDS = 240

type Tone = 'ok' | 'warn' | 'bad'

interface Line {
  at: number
  text: string
  tone?: Tone
  // Consecutive attempts with the same outcome collapse into one line with a count.
  key?: string
  count: number
}

export function BackendGate({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(false)
  // Each increment starts one check. 1 on mount is the initial check.
  const [run, setRun] = useState(1)
  const [visible, setVisible] = useState(false)
  const [lines, setLines] = useState<Line[]>([])
  const [startedAt, setStartedAt] = useState(() => Date.now())
  const [now, setNow] = useState(() => Date.now())
  const checking = useRef(false)
  const skipWait = useRef<(() => void) | null>(null)

  useEffect(() => {
    let cancelled = false
    checking.current = true
    const began = Date.now()
    setStartedAt(began)
    setLines([])

    const log = (text: string, tone?: Tone, key?: string) => {
      if (cancelled) return
      setLines((prev) => {
        const last = prev[prev.length - 1]
        if (key && last?.key === key) {
          return [...prev.slice(0, -1), { ...last, at: Date.now(), count: last.count + 1 }]
        }
        return [...prev, { at: Date.now(), text, tone, key, count: 1 }]
      })
    }

    log(`Contacting ${new URL(HEALTH_URL).host}`)
    // Said once, at the first sign the server is not simply running: an attempt that fails, or no
    // answer after SHOW_AFTER_MS, whichever comes first.
    let explained = false
    const explain = () => {
      if (explained) return
      explained = true
      log(
        'The server is probably asleep. The free tier stops it after 15 minutes without ' +
          'traffic, and this request starts it again.',
      )
    }
    const showTimer = setTimeout(() => {
      setVisible(true)
      explain()
    }, SHOW_AFTER_MS)

    void (async () => {
      let warnedTooLong = false
      for (let attempt = 1; !cancelled; attempt++) {
        const attemptStart = Date.now()
        const result = await probeHealth(HEALTH_URL, ATTEMPT_TIMEOUT_MS)
        if (cancelled) return

        if (result.kind === 'up') {
          clearTimeout(showTimer)
          const seconds = (Date.now() - began) / 1000
          if (seconds * 1000 >= SHOW_AFTER_MS) {
            log(`Server answered after ${Math.round(seconds)}s. Loading StudyLoop.`, 'ok')
            rememberWakeSeconds(seconds)
            // Long enough for the last line to be read before the app replaces the screen.
            await pause(700, skipWait)
            if (cancelled) return
          }
          checking.current = false
          setVisible(false)
          setReady(true)
          return
        }

        explain()
        log(...describe(result))
        if (!warnedTooLong && (Date.now() - began) / 1000 > TOO_LONG_SECONDS) {
          warnedTooLong = true
          log(
            'This is longer than a cold start takes. The server may be redeploying or failing ' +
              'to start. Checks continue.',
            'bad',
          )
        }
        await pause(Math.max(0, attemptStart + ATTEMPT_EVERY_MS - Date.now()), skipWait)
      }
    })()

    return () => {
      cancelled = true
      clearTimeout(showTimer)
      skipWait.current?.()
    }
  }, [run])

  // Re-check when a request is about to reach a server that may have gone to sleep, and when the
  // tab is shown again after long enough for that to have happened.
  useEffect(() => {
    const recheck = () => {
      if (!checking.current) setRun((n) => n + 1)
    }
    const onVisible = () => {
      if (document.visibilityState === 'visible' && isProbablyAsleep()) recheck()
    }
    const unsubscribe = onProbablyAsleep(recheck)
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      unsubscribe()
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [])

  useEffect(() => {
    if (!visible) return
    const timer = setInterval(() => setNow(Date.now()), 1_000)
    return () => clearInterval(timer)
  }, [visible])

  return (
    <>
      {ready && children}
      {visible && (
        <WakeScreen
          lines={lines}
          elapsedSeconds={Math.max(0, Math.floor((now - startedAt) / 1000))}
          onCheckNow={() => skipWait.current?.()}
        />
      )}
    </>
  )
}

function describe(result: Exclude<Probe, { kind: 'up' }>): [string, Tone, string] {
  switch (result.kind) {
    case 'timeout':
      return [`No reply in ${ATTEMPT_TIMEOUT_MS / 1000}s. Still starting.`, 'warn', 'timeout']
    case 'unreachable':
      return navigator.onLine
        ? ['No usable reply yet. Still starting.', 'warn', 'unreachable']
        : ['This device is offline.', 'bad', 'offline']
    case 'down':
      return [
        'The API is running but reports DOWN. Its database check is failing.',
        'bad',
        'down',
      ]
    case 'status':
      return result.status >= 502 && result.status <= 504
        ? [
            `HTTP ${result.status} from Render's proxy. The instance is not serving yet.`,
            'warn',
            `status-${result.status}`,
          ]
        : [`Unexpected HTTP ${result.status} from the health check.`, 'bad', `status-${result.status}`]
  }
}

// A wait that "Check now" can cut short. The resolver is parked in `skip` so the button, and the
// effect cleanup, can end it early.
function pause(ms: number, skip: { current: (() => void) | null }): Promise<void> {
  return new Promise((resolve) => {
    const done = () => {
      clearTimeout(timer)
      skip.current = null
      resolve()
    }
    const timer = setTimeout(done, ms)
    skip.current = done
  })
}

const TONE_CLASS: Record<Tone, string> = {
  ok: 'text-ok',
  warn: 'text-warn',
  bad: 'text-bad',
}

function WakeScreen({
  lines,
  elapsedSeconds,
  onCheckNow,
}: {
  lines: Line[]
  elapsedSeconds: number
  onCheckNow: () => void
}) {
  const measured = lastWakeSeconds()
  const expected = measured ?? DEFAULT_WAKE_SECONDS
  const done = lines[lines.length - 1]?.tone === 'ok'
  // Capped below 100 until the server has actually answered: the estimate is a past wake, not a
  // promise, and a full bar that keeps waiting reads as a hang.
  const percent = done ? 100 : Math.min(95, (elapsedSeconds / expected) * 100)
  const logEnd = useRef<HTMLDivElement>(null)

  useEffect(() => {
    logEnd.current?.scrollIntoView({ block: 'nearest' })
  }, [lines])

  return (
    <div
      role="status"
      aria-live="polite"
      className="warm-key fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-ground px-4 py-10"
    >
      <div className="w-full max-w-[640px]">
        <Eyebrow>StudyLoop</Eyebrow>
        <h1 className="mt-3 mb-2 text-[clamp(26px,5vw,36px)] leading-[1.05] tracking-[-0.03em] text-ink">
          {done ? 'Server is up' : 'Starting the server'}
        </h1>
        <p className="m-0 mb-6 max-w-[56ch] text-sm text-ink-2">
          The API runs on a free instance that sleeps when nobody is using it. The first visit
          after a quiet spell waits for it to boot. The app opens by itself when it answers.
        </p>

        <div className="max-h-[42vh] overflow-y-auto rounded-card border border-line bg-ground-2 px-4 py-3 font-mono text-[12px] leading-[1.7]">
          {lines.map((line, i) => (
            <div key={i} className="flex gap-3">
              <span className="tnum shrink-0 text-ink-muted">{clock(line.at)}</span>
              <span className={cx('min-w-0', line.tone ? TONE_CLASS[line.tone] : 'text-ink-2')}>
                {line.text}
                {line.count > 1 && <span className="text-ink-muted"> ×{line.count}</span>}
              </span>
            </div>
          ))}
          <div ref={logEnd} />
        </div>

        {/* No percentage on this bar: its length is elapsed time over a past wake, which is an
            estimate, and a number would present it as a measurement. */}
        <div className="mt-5 mb-1.5 flex items-baseline justify-between gap-3">
          <span className="tnum font-mono text-[11.5px] text-ink-2">{elapsedSeconds}s elapsed</span>
          <span className="font-mono text-[11.5px] text-ink-muted">
            {measured ? `last wake took ${measured}s` : 'usually about a minute'}
          </span>
        </div>
        <div className="h-[3px] overflow-hidden rounded-full bg-ground-2">
          <div
            className="h-full rounded-full bg-accent transition-all duration-1000 ease-linear"
            style={{ width: `${percent}%` }}
          />
        </div>

        {!done && (
          <div className="mt-5">
            <Button size="sm" onClick={onCheckNow}>
              Check now
            </Button>
          </div>
        )}
      </div>
    </div>
  )
}

function clock(ms: number): string {
  return new Date(ms).toLocaleTimeString([], { hour12: false })
}
