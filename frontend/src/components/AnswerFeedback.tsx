import { useState } from 'react'
import { chatApi } from '../lib/api'
import type { Citation } from '../lib/types'
import { Button } from './ui'

// Phase 28.3 — was this answer any good.
//
// **Two buttons, not five stars.** A scale invites an average, and an average of opinions about
// answers is not a number anybody can act on. "This was wrong" is.
//
// The thumbs-down opens a one-line box, and that box is the point of the feature: the verdict on
// its own is unactionable, while the verdict plus the passages the answer read separates the two
// failures that need opposite fixes — retrieval brought the wrong pages, or retrieval was right and
// the writing was wrong. The citations go with it exactly as they are on screen.
export function AnswerFeedback({
  courseId,
  answerEventId,
  question,
  citations,
}: {
  courseId: string
  // Null when question logging is switched off. The control hides itself rather than offering a
  // button whose result would have nothing to attach to.
  answerEventId: string | null
  question: string
  citations: Citation[]
}) {
  const [verdict, setVerdict] = useState<'up' | 'down' | null>(null)
  const [reason, setReason] = useState('')
  const [state, setState] = useState<'idle' | 'sending' | 'sent' | 'error'>('idle')

  if (!answerEventId) return null

  async function send(helpful: boolean, withReason: string | null) {
    setState('sending')
    try {
      await chatApi.feedback(courseId, {
        answerEventId,
        helpful,
        reason: withReason,
        question,
        citations,
      })
      setState('sent')
    } catch {
      setState('error')
    }
  }

  // A thumbs-up is one click and done — there is nothing to ask somebody who is happy. A
  // thumbs-down records immediately too, and *then* asks why: the verdict is the part that must
  // land, and making it wait on a sentence would lose every report from somebody who could not be
  // bothered to write one.
  if (state === 'sent' && verdict === 'up') {
    return <p className="mt-3 mb-0 text-[12px] text-ink-muted">Thanks — noted.</p>
  }

  if (verdict === 'down') {
    return (
      <div className="mt-3 border-t border-line-soft pt-2.5">
        {state === 'sent' && reason.trim().length > 0 ? (
          <p className="m-0 text-[12px] text-ink-muted">
            Thanks — your instructor sees this with the passages the answer read.
          </p>
        ) : (
          <>
            <label className="mb-1.5 block text-[12px] text-ink-muted" htmlFor="feedback-reason">
              What should it have said? Optional, and the most useful thing you can leave.
            </label>
            <div className="flex flex-wrap items-center gap-2">
              <input
                id="feedback-reason"
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                placeholder="It missed the section on rotations…"
                className="min-w-0 flex-1 rounded-card border border-line bg-ground-2 px-2.5 py-1.5 text-[13px] text-ink outline-none focus:border-accent"
              />
              <Button
                variant="quiet"
                size="sm"
                disabled={state === 'sending' || reason.trim().length === 0}
                onClick={() => void send(false, reason.trim())}
              >
                {state === 'sending' ? 'Sending…' : 'Send'}
              </Button>
            </div>
            <p className="mt-2 mb-0 text-[12px] text-ink-muted">
              {state === 'error'
                ? 'That did not send — try again.'
                : 'Already recorded. Adding a line makes it useful.'}
            </p>
          </>
        )}
      </div>
    )
  }

  return (
    <div className="mt-3 flex items-center gap-2">
      <span className="text-[12px] text-ink-muted">Was this right?</span>
      <Button
        variant="quiet"
        disabled={state === 'sending'}
        onClick={() => {
          setVerdict('up')
          void send(true, null)
        }}
      >
        Yes
      </Button>
      <Button
        variant="quiet"
        disabled={state === 'sending'}
        onClick={() => {
          setVerdict('down')
          void send(false, null)
        }}
      >
        No
      </Button>
    </div>
  )
}
