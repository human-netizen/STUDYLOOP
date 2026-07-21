import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ApiError, invitesApi } from '../lib/api'
import type { InvitePreviewResponse } from '../lib/types'
import { AppHeader } from '../components/AppHeader'

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
    <div className="min-h-screen bg-slate-50 dark:bg-slate-950">
      <AppHeader />
      <main className="mx-auto flex max-w-md flex-col items-center px-6 py-16 text-center">
        {loading && <p className="text-slate-500 dark:text-slate-400">Loading invite…</p>}

        {!loading && error && !preview && (
          <>
            <p className="text-red-500">{error}</p>
            <BackLink navigate={navigate} />
          </>
        )}

        {preview && (
          <div className="w-full rounded-2xl border border-slate-200 bg-white p-8 dark:border-slate-800 dark:bg-slate-900">
            <p className="text-sm text-slate-500 dark:text-slate-400">You've been invited to join</p>
            <h1 className="mt-1 text-2xl font-semibold tracking-tight text-slate-900 dark:text-slate-100">
              {preview.courseName}
            </h1>
            <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
              as <span className="font-medium">{preview.role}</span>
            </p>
            {preview.requiresMatchingEmail && (
              <p className="mt-3 text-xs text-slate-400">
                This invite is tied to a specific email address.
              </p>
            )}
            {error && <p className="mt-4 text-sm text-red-500">{error}</p>}
            <button
              type="button"
              onClick={accept}
              disabled={joining}
              className="mt-6 w-full rounded-lg bg-indigo-600 px-4 py-2 font-medium text-white transition hover:bg-indigo-500 disabled:opacity-60"
            >
              {joining ? 'Joining…' : 'Join course'}
            </button>
            <BackLink navigate={navigate} />
          </div>
        )}
      </main>
    </div>
  )
}

function BackLink({ navigate }: { navigate: (to: string) => void }) {
  return (
    <button
      type="button"
      onClick={() => navigate('/')}
      className="mt-4 text-sm text-slate-500 hover:underline dark:text-slate-400"
    >
      Back to your courses
    </button>
  )
}
