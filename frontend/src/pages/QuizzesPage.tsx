import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError, coursesApi, quizzesApi } from '../lib/api'
import type { CourseResponse, QuizSummary } from '../lib/types'
import { AppShell } from '../components/AppShell'
import {
  Button,
  Empty,
  ErrorText,
  Loading,
  Meta,
  NumberField,
  PageTitle,
  Panel,
  Row,
  Rows,
  SectionHead,
} from '../components/ui'

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
    <AppShell courseName={course?.name}>
      <PageTitle
        eyebrow={course?.name ?? 'Course'}
        title="Quizzes"
        sub="Generated from this course's ready documents, then graded for you."
      />

      {loading && <Loading />}
      {error && <ErrorText>{error}</ErrorText>}

      {!loading && (
        <>
          <section className="mb-14">
            <SectionHead index="01 · New" title="Generate a quiz" />
            <GenerateQuiz
              courseId={id}
              onGenerated={(quizId) => navigate(`/courses/${id}/quizzes/${quizId}`)}
            />
          </section>

          <section>
            <SectionHead
              index="02 · Saved"
              title="This course's quizzes"
              description={quizzes.length > 0 ? `${quizzes.length} available` : undefined}
            />
            {quizzes.length === 0 ? (
              <Empty>No quizzes yet — generate one from your materials above.</Empty>
            ) : (
              <Rows>
                {quizzes.map((quiz) => (
                  <Row key={quiz.id} interactive>
                    <Link
                      to={`/courses/${id}/quizzes/${quiz.id}`}
                      className="group flex items-center justify-between gap-4 px-5 py-4 no-underline"
                    >
                      <div className="min-w-0">
                        <p className="m-0 truncate font-display text-[17px] font-bold tracking-[-0.015em] text-ink">
                          {quiz.title}
                        </p>
                        <Meta>
                          {quiz.questionCount} question{quiz.questionCount === 1 ? '' : 's'} ·{' '}
                          {new Date(quiz.createdAt).toLocaleDateString()}
                        </Meta>
                      </div>
                      <span className="shrink-0 font-mono text-[11px] tracking-[0.08em] text-ink-muted uppercase transition duration-150 group-hover:text-ink">
                        Take →
                      </span>
                    </Link>
                  </Row>
                ))}
              </Rows>
            )}
          </section>
        </>
      )}
    </AppShell>
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
    <Panel>
      <div className="flex flex-wrap items-end gap-4">
        <NumberField
          label="Multiple choice"
          value={mcCount}
          min={0}
          max={20}
          disabled={generating}
          onChange={setMcCount}
        />
        <NumberField
          label="Short answer"
          value={saCount}
          min={0}
          max={20}
          disabled={generating}
          onChange={setSaCount}
        />
        <Button variant="primary" onClick={() => void generate()} disabled={generating}>
          {generating ? 'Generating…' : 'Generate'}
        </Button>
      </div>
      <Meta className="mt-4 block">Built from every READY document in this course.</Meta>
      {error && <ErrorText className="mt-3">{error}</ErrorText>}
    </Panel>
  )
}
