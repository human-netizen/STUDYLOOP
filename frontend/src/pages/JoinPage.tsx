import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ApiError, invitesApi } from '../lib/api'
import type { InvitePreviewResponse } from '../lib/types'
import { AppShell } from '../components/AppShell'
import { Button, ErrorText, Eyebrow, Loading, Meta, Panel, Pill } from '../components/ui'

export function JoinPage() {
  const { token = '' } = useParams()
  const navigate = useNavigate()

  const [preview, setPreview] = useState<InvitePreviewResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [joining, setJoining] = useState(false)

  useEffect(() => {
    invitesApi
      .preview(token)
      .then(setPreview)
      .catch((err) => setError(err instanceof ApiError ? err.message : 'This invite could not be loaded.'))
      .finally(() => setLoading(false))
  }, [token])

  async function accept() {
    setError(null)
    setJoining(true)
    try {
      await invitesApi.accept(token)
      navigate('/', { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not join the course.')
    } finally {
      setJoining(false)
    }
  }

  return (
    <AppShell>
      <div className="max-w-lg">
        {loading && <Loading>Loading invite</Loading>}

        {!loading && error && !preview && (
          <>
            <ErrorText>{error}</ErrorText>
            <Button variant="quiet" onClick={() => navigate('/')} className="mt-4">
              Back to your courses
            </Button>
          </>
        )}

        {preview && (
          <Panel className="flex flex-col gap-5">
            <div>
              <Eyebrow>You've been invited to join</Eyebrow>
              <h1 className="mt-2 mb-0 text-[clamp(28px,3.6vw,42px)] leading-[1] tracking-[-0.035em]">
                {preview.courseName}
              </h1>
            </div>

            <div className="flex items-center gap-3">
              <Meta>as</Meta>
              <Pill>{preview.role}</Pill>
            </div>

            {preview.requiresMatchingEmail && (
              <Meta>This invite is tied to a specific email address.</Meta>
            )}

            {error && <ErrorText>{error}</ErrorText>}

            <div className="flex items-center gap-3 border-t border-line pt-5">
              <Button variant="primary" onClick={accept} disabled={joining}>
                {joining ? 'Joining…' : 'Join course'}
              </Button>
              <Button variant="quiet" onClick={() => navigate('/')}>
                Not now
              </Button>
            </div>
          </Panel>
        )}
      </div>
    </AppShell>
  )
}
