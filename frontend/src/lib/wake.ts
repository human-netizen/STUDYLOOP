// Render's free tier stops the API after 15 minutes without inbound traffic, and the next request
// starts it again: a container boot plus a Spring context on 0.1 vCPU, which takes about a minute.
// The browser sees none of that — a request simply hangs or fails — so this module records the two
// facts the wake screen (components/BackendGate) is built from: when the server last answered, and
// whether it answers now.

// Render's limit is 15 minutes; one minute of margin so a request made at 14:30 is treated as
// possibly waking the server rather than assumed to find it running.
const PROBABLY_ASLEEP_AFTER_MS = 14 * 60_000

let lastContact = 0
const asleepListeners = new Set<() => void>()

function markContact() {
  lastContact = Date.now()
}

export function isProbablyAsleep(): boolean {
  return Date.now() - lastContact > PROBABLY_ASLEEP_AFTER_MS
}

// Called when a request is about to go to a server that has been silent long enough to be asleep.
// Returns the unsubscribe function, so it can be the whole body of a useEffect.
export function onProbablyAsleep(listener: () => void): () => void {
  asleepListeners.add(listener)
  return () => {
    asleepListeners.delete(listener)
  }
}

// Every API call goes through this instead of fetch. `lastContact > 0` skips the notification
// before the first successful probe, when the gate is already checking.
//
// 502/503/504 are not counted as contact: on Render they come from the platform's proxy while the
// instance is down or starting, so they say nothing about the application being up.
export async function trackedFetch(input: string, init?: RequestInit): Promise<Response> {
  if (lastContact > 0 && isProbablyAsleep()) asleepListeners.forEach((listener) => listener())
  const res = await fetch(input, init)
  if (res.status < 502 || res.status > 504) markContact()
  return res
}

// One health check, classified. `down` is the application answering with Spring's aggregate
// DOWN — it is running, but a component (in practice the database) is failing its check.
export type Probe =
  | { kind: 'up' }
  | { kind: 'down' }
  | { kind: 'status'; status: number }
  | { kind: 'timeout' }
  | { kind: 'unreachable' }

// GET with no custom headers is a CORS "simple request", so there is no preflight: one round trip
// per attempt. `unreachable` is fetch's TypeError, which covers a refused connection, DNS failure,
// no network, and a response the browser refused to hand over for lack of CORS headers — a page
// served by Render rather than by the application has none.
export async function probeHealth(url: string, timeoutMs: number): Promise<Probe> {
  try {
    const res = await fetch(url, { cache: 'no-store', signal: AbortSignal.timeout(timeoutMs) })
    const body = (await res.json().catch(() => null)) as { status?: string } | null
    if (res.ok && body?.status === 'UP') {
      markContact()
      return { kind: 'up' }
    }
    if (body?.status === 'DOWN') return { kind: 'down' }
    return { kind: 'status', status: res.status }
  } catch (err) {
    return err instanceof DOMException && err.name === 'TimeoutError'
      ? { kind: 'timeout' }
      : { kind: 'unreachable' }
  }
}

// How long the last wake took, so the progress bar is scaled to this deployment rather than to a
// guess. Browser storage is a convenience here: when it is unavailable the bar uses the default.
const WAKE_KEY = 'studyloop.lastWakeSeconds'

export function lastWakeSeconds(): number | null {
  try {
    const value = Number(localStorage.getItem(WAKE_KEY))
    return Number.isFinite(value) && value > 0 ? value : null
  } catch {
    return null
  }
}

export function rememberWakeSeconds(seconds: number) {
  try {
    localStorage.setItem(WAKE_KEY, String(Math.round(seconds)))
  } catch {
    // storage blocked — the next wake uses the default estimate
  }
}
