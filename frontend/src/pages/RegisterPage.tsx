import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../lib/auth'
import { ApiError } from '../lib/api'
import { AuthField, AuthShell, SubmitButton } from '../components/AuthForm'
import { ErrorText } from '../components/ui'

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
    <AuthShell
      title="Create your account"
      intro="Takes a minute. No card, no email confirmation."
      footer={
        <>
          Already have an account?{' '}
          <Link to="/login" className="font-medium text-ink underline decoration-accent underline-offset-4">
            Sign in
          </Link>
        </>
      }
    >
      <form onSubmit={onSubmit} className="flex flex-col gap-4">
        <AuthField label="Name" type="text" value={displayName} onChange={setDisplayName} autoComplete="name" />
        <AuthField label="Email" type="email" value={email} onChange={setEmail} autoComplete="email" />
        <AuthField
          label="Password"
          type="password"
          value={password}
          onChange={setPassword}
          autoComplete="new-password"
        />
        {error && <ErrorText>{error}</ErrorText>}
        <SubmitButton submitting={submitting}>Create account</SubmitButton>
      </form>
    </AuthShell>
  )
}
