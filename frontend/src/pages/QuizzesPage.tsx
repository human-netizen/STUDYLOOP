import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError, coursesApi, quizzesApi } from '../lib/api'
import type { CourseResponse, QuizSummary } from '../lib/types'
import { AppHeader } from '../components/AppHeader'

// A course's quizzes: generate a new one from the course's ready materials, or open an existing
// one to take. Quizzes are shared across the course.
export function QuizzesPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()

  const [course, setCourse] = useState<CourseResponse | null>(null)
  const [quizzes, setQuizzes] = useState<QuizSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    Promise.all([coursesApi.get(id), quizzesApi.list(id)])
      .then(([courseData, list]) => {
        if (!active) return
        setCourse(courseData)
        setQuizzes(list)
      })
      .catch((err) => {
        if (active) setError(err instanceof ApiError ? err.message : 'Failed to load quizzes.')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [id])

  return (
    <div className="min-h-screen bg-slate-50 dark:bg-slate-950">
      <AppHeader />
      <main className="mx-auto max-w-3xl px-6 py-10">
        <Link to={`/courses/${id}`} className="text-sm text-slate-500 hover:underline dark:text-slate-400">
          ← {course ? course.name : 'Course'}
        </Link>

        <h1 className="mt-4 text-2xl font-semibold tracking-tight text-slate-900 dark:text-slate-100">
          Quizzes
        </h1>

        {loading && <p className="mt-6 text-slate-500 dark:text-slate-400">Loading…</p>}
        {error && <p className="mt-6 text-sm text-red-500">{error}</p>}

        {!loading && (
          <>
            <div className="mt-8">
              <GenerateQuiz
                courseId={id}
                onGenerated={(quizId) => navigate(`/courses/${id}/quizzes/${quizId}`)}
              />
            </div>

            <h2 className="mt-10 mb-4 text-lg font-semibold tracking-tight text-slate-900 dark:text-slate-100">
              Your course's quizzes
            </h2>
            {quizzes.length === 0 ? (
              <p className="text-slate-500 dark:text-slate-400">
                No quizzes yet — generate one from your materials above.
              </p>
            ) : (
              <ul className="flex flex-col gap-3">
                {quizzes.map((quiz) => (
                  <li key={quiz.id}>
                    <Link
                      to={`/courses/${id}/quizzes/${quiz.id}`}
                      className="flex items-center justify-between gap-4 rounded-xl border border-slate-200 bg-white p-4 transition hover:border-indigo-400 dark:border-slate-800 dark:bg-slate-900"
                    >
                      <div className="min-w-0">
                        <p className="truncate font-medium text-slate-900 dark:text-slate-100">
                          {quiz.title}
                        </p>
                        <p className="text-xs text-slate-400">
                          {quiz.questionCount} question{quiz.questionCount === 1 ? '' : 's'} ·{' '}
                          {new Date(quiz.createdAt).toLocaleDateString()}
                        </p>
                      </div>
                      <span className="shrink-0 text-sm font-medium text-indigo-600 dark:text-indigo-400">
                        Take →
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </main>
    </div>
  )
}

function GenerateQuiz({
  courseId,
  onGenerated,
}: {
  courseId: string
  onGenerated: (quizId: string) => void
}) {
  const [mcCount, setMcCount] = useState(5)
  const [saCount, setSaCount] = useState(2)
  const [generating, setGenerating] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function generate() {
    setError(null)
    setGenerating(true)
    try {
      const quiz = await quizzesApi.generate(courseId, {
        multipleChoiceCount: mcCount,
        shortAnswerCount: saCount,
      })
      onGenerated(quiz.id)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not generate a quiz.')
      setGenerating(false)
    }
  }

  return (
    <div className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
      <p className="font-medium text-slate-800 dark:text-slate-100">Generate a quiz</p>
      <p className="mt-1 text-xs text-slate-400">
        Built from all ready documents in this course.
      </p>
      <div className="mt-4 flex flex-wrap items-end gap-4">
        <NumberField
          label="Multiple choice"
          value={mcCount}
          onChange={setMcCount}
          disabled={generating}
        />
        <NumberField
          label="Short answer"
          value={saCount}
          onChange={setSaCount}
          disabled={generating}
        />
        <button
          type="button"
          onClick={() => void generate()}
          disabled={generating}
          className="rounded-xl bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-indigo-500 disabled:opacity-60"
        >
          {generating ? 'Generating…' : 'Generate'}
        </button>
      </div>
      {error && <p className="mt-3 text-sm text-red-500">{error}</p>}
    </div>
  )
}

function NumberField({
  label,
  value,
  onChange,
  disabled,
}: {
  label: string
  value: number
  onChange: (value: number) => void
  disabled: boolean
}) {
  return (
    <label className="flex flex-col gap-1 text-xs font-medium text-slate-500 dark:text-slate-400">
      {label}
      <input
        type="number"
        min={0}
        max={20}
        value={value}
        disabled={disabled}
        onChange={(event) => {
          const next = Number(event.target.value)
          onChange(Number.isFinite(next) ? Math.min(20, Math.max(0, next)) : 0)
        }}
        className="w-24 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-900 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100"
      />
    </label>
  )
}
