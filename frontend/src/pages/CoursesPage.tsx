import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError, coursesApi } from '../lib/api'
import type { CourseResponse } from '../lib/types'
import { AppShell } from '../components/AppShell'
import {
  Button,
  Empty,
  ErrorText,
  Eyebrow,
  Field,
  Input,
  Loading,
  Meta,
  PageTitle,
  Panel,
  Pill,
  Row,
  Rows,
  SectionHead,
} from '../components/ui'
import { PANEL, cx } from '../lib/style'

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
    <AppShell>
      <PageTitle
        eyebrow="Workspace"
        title="Your courses"
        sub="Each course holds its own materials, quizzes and cards."
      />

      <section className="mb-14">
        <SectionHead index="01 · Start" title="Add a course" description="Create one, or join with an invite." />
        <div className="grid gap-4 lg:grid-cols-[1.4fr_1fr]">
          <CreateCourseCard onCreated={addCourse} />
          <JoinCourseCard />
        </div>
      </section>

      <section>
        <SectionHead
          index="02 · Library"
          title="Courses"
          description={courses.length > 0 ? `${courses.length} in your library` : undefined}
        />

        {loading && <Loading />}
        {error && <ErrorText>{error}</ErrorText>}

        {!loading && !error && courses.length === 0 && (
          <Empty>No courses yet — create one above, or paste an invite link.</Empty>
        )}

        {courses.length > 0 && (
          <Rows>
            {courses.map((course) => (
              <Row key={course.id} interactive>
                <Link
                  to={`/courses/${course.id}`}
                  className="group flex items-center justify-between gap-4 px-5 py-4 no-underline"
                >
                  <div className="min-w-0">
                    <p className="m-0 truncate font-display text-[17px] font-bold tracking-[-0.015em] text-ink">
                      {course.name}
                    </p>
                    {course.description && (
                      <p className="m-0 truncate text-sm text-ink-muted">{course.description}</p>
                    )}
                  </div>
                  <div className="flex shrink-0 items-center gap-3">
                    <Pill>{course.myRole}</Pill>
                    <span
                      aria-hidden
                      className="text-ink-muted transition duration-150 group-hover:translate-x-0.5 group-hover:text-ink"
                    >
                      →
                    </span>
                  </div>
                </Link>
              </Row>
            ))}
          </Rows>
        )}
      </section>
    </AppShell>
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
    <form onSubmit={onSubmit} className={cx(PANEL, 'flex flex-col gap-4')}>
      <div>
        <Eyebrow>New</Eyebrow>
        <h3 className="mt-1 mb-0 text-[17px] tracking-[-0.015em]">Create a course</h3>
      </div>
      <Field label="Name">
        <Input
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder="e.g. Operating Systems"
          required
        />
      </Field>
      <Field label="Description — optional">
        <Input
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          placeholder="One line about what it covers"
        />
      </Field>
      {error && <ErrorText>{error}</ErrorText>}
      <Button type="submit" variant="primary" disabled={submitting} className="self-start">
        {submitting ? 'Creating…' : 'Create course'}
      </Button>
    </form>
  )
}

function JoinCourseCard() {
  const navigate = useNavigate()
  const [value, setValue] = useState('')

  function submit() {
    const token = parseInviteToken(value)
    if (token) navigate(`/join/${token}`)
  }

  return (
    <Panel className="flex flex-col gap-4">
      <div>
        <Eyebrow>Invited</Eyebrow>
        <h3 className="mt-1 mb-0 text-[17px] tracking-[-0.015em]">Join a course</h3>
      </div>
      <Field label="Invite link or token">
        <Input
          value={value}
          onChange={(event) => setValue(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault()
              submit()
            }
          }}
          placeholder="Paste it here"
          className="font-mono text-[12.5px]"
        />
      </Field>
      <Button onClick={submit} disabled={!value.trim()} className="self-start">
        Continue
      </Button>
      <Meta>The token is the last segment of the link.</Meta>
    </Panel>
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
