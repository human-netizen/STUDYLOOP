import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../lib/auth'
import { ApiError } from '../lib/api'
import { AuthShell, Field, SubmitButton } from '../components/AuthForm'

export function RegisterPage() {
  const { register } = useAuth()
  const navigate = useNavigate()

  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await register({ displayName, email, password })
      navigate('/', { replace: true })
    } catch (err) {
      // Prefer a specific field message (e.g. password too short) when the backend sends one.
      const message =
        err instanceof ApiError
          ? (err.fieldErrors ? Object.values(err.fieldErrors)[0] : undefined) ?? err.message
          : 'Something went wrong. Try again.'
      setError(message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AuthShell title="Create your account">
      <form onSubmit={onSubmit} className="flex flex-col gap-4">
        <Field label="Name" type="text" value={displayName} onChange={setDisplayName} autoComplete="name" />
        <Field label="Email" type="email" value={email} onChange={setEmail} autoComplete="email" />
        <Field
          label="Password"
          type="password"
          value={password}
          onChange={setPassword}
          autoComplete="new-password"
        />
        {error && <p className="text-sm text-red-500">{error}</p>}
        <SubmitButton submitting={submitting}>Create account</SubmitButton>
      </form>
      <p className="mt-6 text-center text-sm text-slate-500 dark:text-slate-400">
        Already have an account?{' '}
        <Link to="/login" className="font-medium text-indigo-600 hover:underline dark:text-indigo-400">
          Sign in
        </Link>
      </p>
    </AuthShell>
  )
}
