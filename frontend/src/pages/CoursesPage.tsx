import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError, coursesApi } from '../lib/api'
import type { CourseResponse } from '../lib/types'
import { AppHeader } from '../components/AppHeader'

export function CoursesPage() {
  const [courses, setCourses] = useState<CourseResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    coursesApi
      .list()
      .then((page) => setCourses(page.content))
      .catch((err) => setError(err instanceof ApiError ? err.message : 'Failed to load courses.'))
      .finally(() => setLoading(false))
  }, [])

  function addCourse(course: CourseResponse) {
    // New courses sort newest-first, matching the backend's default ordering.
    setCourses((current) => [course, ...current])
  }

  return (
    <div className="min-h-screen bg-slate-50 dark:bg-slate-950">
      <AppHeader />
      <main className="mx-auto max-w-3xl px-6 py-10">
        <div className="grid gap-4 sm:grid-cols-2">
          <CreateCourseCard onCreated={addCourse} />
          <JoinCourseCard />
        </div>

        <h1 className="mt-10 mb-4 text-xl font-semibold tracking-tight text-slate-900 dark:text-slate-100">
          Your courses
        </h1>

        {loading && <p className="text-slate-500 dark:text-slate-400">Loading…</p>}
        {error && <p className="text-sm text-red-500">{error}</p>}
        {!loading && !error && courses.length === 0 && (
          <p className="text-slate-500 dark:text-slate-400">
            No courses yet — create one above or join with a link.
          </p>
        )}

        <ul className="flex flex-col gap-3">
          {courses.map((course) => (
            <li key={course.id}>
              <Link
                to={`/courses/${course.id}`}
                className="flex items-center justify-between rounded-xl border border-slate-200 bg-white p-4 transition hover:border-indigo-400 dark:border-slate-800 dark:bg-slate-900 dark:hover:border-indigo-500"
              >
                <div>
                  <p className="font-medium text-slate-900 dark:text-slate-100">{course.name}</p>
                  {course.description && (
                    <p className="text-sm text-slate-500 dark:text-slate-400">{course.description}</p>
                  )}
                </div>
                <RoleBadge role={course.myRole} />
              </Link>
            </li>
          ))}
        </ul>
      </main>
    </div>
  )
}

function RoleBadge({ role }: { role: CourseResponse['myRole'] }) {
  return (
    <span className="rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-medium text-slate-600 dark:bg-slate-800 dark:text-slate-300">
      {role}
    </span>
  )
}

function CreateCourseCard({ onCreated }: { onCreated: (course: CourseResponse) => void }) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      const course = await coursesApi.create({ name, description: description || undefined })
      onCreated(course)
      setName('')
      setDescription('')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not create the course.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form
      onSubmit={onSubmit}
      className="flex flex-col gap-3 rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900"
    >
      <h2 className="font-medium text-slate-900 dark:text-slate-100">New course</h2>
      <input
        value={name}
        onChange={(event) => setName(event.target.value)}
        placeholder="Course name"
        required
        className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-indigo-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
      />
      <input
        value={description}
        onChange={(event) => setDescription(event.target.value)}
        placeholder="Description (optional)"
        className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-indigo-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
      />
      {error && <p className="text-sm text-red-500">{error}</p>}
      <button
        type="submit"
        disabled={submitting}
        className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-indigo-500 disabled:opacity-60"
      >
        {submitting ? 'Creating…' : 'Create course'}
      </button>
    </form>
  )
}

function JoinCourseCard() {
  const navigate = useNavigate()
  const [value, setValue] = useState('')

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    const token = parseInviteToken(value)
    if (token) navigate(`/join/${token}`)
  }

  return (
    <form
      onSubmit={onSubmit}
      className="flex flex-col gap-3 rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900"
    >
      <h2 className="font-medium text-slate-900 dark:text-slate-100">Join a course</h2>
      <input
        value={value}
        onChange={(event) => setValue(event.target.value)}
        placeholder="Paste an invite link or token"
        required
        className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-indigo-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
      />
      <button
        type="submit"
        className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 transition hover:bg-slate-100 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800"
      >
        Continue
      </button>
    </form>
  )
}

// Accepts a bare token or a pasted invite path/URL (e.g. .../invites/<token>/accept) and
// pulls the token out of it.
function parseInviteToken(input: string): string {
  const parts = input.trim().split('/').filter(Boolean)
  if (parts.length === 0) return ''
  const marker = parts.indexOf('invites')
  if (marker >= 0 && parts[marker + 1]) return parts[marker + 1]
  const last = parts[parts.length - 1]
  return last === 'accept' ? (parts[parts.length - 2] ?? '') : last
}
