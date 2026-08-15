import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { ApiError, quizzesApi } from '../lib/api'
import type { AnswerInput, AttemptResponse, GradedAnswer, Quiz, QuizQuestionView } from '../lib/types'
import { AppShell, BackLink } from '../components/AppShell'
import { Button, ErrorText, Eyebrow, Loading, Pill, TextArea } from '../components/ui'
import { cx } from '../lib/style'

// Take one quiz and see it graded. While taking, answers are held locally; on submit the server
// grades them (revealing the answer key + explanations), and the page switches to a review of the
// attempt. "Retake" clears the answers and starts over.
export function QuizPage() {
  const { id = '', quizId = '' } = useParams()

  const [quiz, setQuiz] = useState<Quiz | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [answers, setAnswers] = useState<Record<string, AnswerInput>>({})
  const [result, setResult] = useState<AttemptResponse | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    quizzesApi
      .get(id, quizId)
      .then((data) => {
        if (active) setQuiz(data)
      })
      .catch((err) => {
        if (active) setError(err instanceof ApiError ? err.message : 'Failed to load this quiz.')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [id, quizId])

  function setOption(questionId: string, optionIndex: number) {
    setAnswers((current) => ({ ...current, [questionId]: { questionId, selectedOptionIndex: optionIndex } }))
  }

  function setText(questionId: string, text: string) {
    setAnswers((current) => ({ ...current, [questionId]: { questionId, answerText: text } }))
  }

  async function submit() {
    if (!quiz) return
    setSubmitError(null)
    setSubmitting(true)
    try {
      const graded = await quizzesApi.submit(id, quizId, { answers: Object.values(answers) })
      setResult(graded)
      window.scrollTo({ top: 0, behavior: 'smooth' })
    } catch (err) {
      setSubmitError(err instanceof ApiError ? err.message : 'Could not submit your answers.')
    } finally {
      setSubmitting(false)
    }
  }

  function retake() {
    setAnswers({})
    setResult(null)
    setSubmitError(null)
  }

  const answered = quiz ? quiz.questions.filter((q) => answers[q.id] !== undefined).length : 0

  return (
    <AppShell>
      <BackLink to={`/courses/${id}/quizzes`}>Quizzes</BackLink>

      {loading && <Loading />}
      {error && <ErrorText>{error}</ErrorText>}

      {quiz && (
        <>
          <div className="mb-8">
            <Eyebrow className="mb-2">
              {quiz.questions.length} question{quiz.questions.length === 1 ? '' : 's'}
              {!result && ` · ${answered} answered`}
            </Eyebrow>
            <h1 className="m-0 text-[clamp(30px,3.8vw,46px)] leading-[1] tracking-[-0.035em]">
              {quiz.title}
            </h1>
          </div>

          {result && <ScoreBanner score={result.score} total={result.total} onRetake={retake} />}

          <ol className="m-0 flex list-none flex-col gap-4 p-0">
            {quiz.questions.map((question) => {
              const graded = result?.answers.find((a) => a.questionId === question.id)
              return graded ? (
                <ReviewQuestion key={question.id} graded={graded} />
              ) : (
                <TakeQuestion
                  key={question.id}
                  question={question}
                  answer={answers[question.id]}
                  onOption={setOption}
                  onText={setText}
                  disabled={submitting}
                />
              )
            })}
          </ol>

          {!result && (
            <div className="mt-8 border-t border-line pt-6">
              <Button variant="primary" onClick={() => void submit()} disabled={submitting}>
                {submitting ? 'Grading…' : 'Submit answers'}
              </Button>
              {submitError && <ErrorText className="mt-3">{submitError}</ErrorText>}
            </div>
          )}
        </>
      )}
    </AppShell>
  )
}

function ScoreBanner({ score, total, onRetake }: { score: number; total: number; onRetake: () => void }) {
  const pct = total === 0 ? 0 : Math.round((score / total) * 100)
  return (
    <div className="mb-10 rounded-card border border-line bg-surface p-6 shadow-card">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <Eyebrow className="mb-1">Your score</Eyebrow>
          <p className="tnum m-0 font-display text-[clamp(40px,6vw,54px)] leading-[0.95] font-bold tracking-[-0.04em] text-ink">
            {score}
            <span className="text-ink-muted">/{total}</span>
          </p>
        </div>
        <div className="flex items-center gap-4">
          <span className="tnum font-mono text-[13px] text-ink-2">{pct}%</span>
          <Button onClick={onRetake}>Retake</Button>
        </div>
      </div>
      {/* One thin accent bar — the only place a percentage gets drawn. */}
      <div className="mt-5 h-[6px] overflow-hidden rounded-full bg-ground-2">
        <div className="h-full rounded-full bg-accent transition-all duration-500" style={{ width: `${pct}%` }} />
      </div>
    </div>
  )
}

function TakeQuestion({
  question,
  answer,
  onOption,
  onText,
  disabled,
}: {
  question: QuizQuestionView
  answer: AnswerInput | undefined
  onOption: (questionId: string, optionIndex: number) => void
  onText: (questionId: string, text: string) => void
  disabled: boolean
}) {
  return (
    <li className="rounded-card border border-line bg-surface p-5 shadow-card">
      <div className="flex gap-3">
        <span className="tnum shrink-0 font-mono text-[11px] leading-6 text-ink-muted">
          {String(question.index + 1).padStart(2, '0')}
        </span>
        <p className="m-0 font-medium text-ink">{question.prompt}</p>
      </div>

      {question.type === 'MULTIPLE_CHOICE' ? (
        <div className="mt-4 flex flex-col gap-1.5 pl-8">
          {question.options.map((option, optionIndex) => {
            const selected = answer?.selectedOptionIndex === optionIndex
            return (
              <label
                key={optionIndex}
                className={cx(
                  'flex cursor-pointer items-center gap-3 rounded-ctl border border-l-2 px-3 py-2 text-sm transition duration-150',
                  selected
                    ? 'border-line border-l-accent bg-surface-2 text-ink'
                    : 'border-line-soft border-l-transparent text-ink-2 hover:bg-surface-2',
                )}
              >
                <input
                  type="radio"
                  name={question.id}
                  checked={selected}
                  disabled={disabled}
                  onChange={() => onOption(question.id, optionIndex)}
                />
                <span>{option}</span>
              </label>
            )
          })}
        </div>
      ) : (
        <TextArea
          value={answer?.answerText ?? ''}
          disabled={disabled}
          onChange={(event) => onText(question.id, event.target.value)}
          rows={3}
          placeholder="Your answer…"
          className="mt-4 ml-8 w-[calc(100%-2rem)]"
        />
      )}
    </li>
  )
}

function ReviewQuestion({ graded }: { graded: GradedAnswer }) {
  return (
    <li
      className={cx(
        'rounded-card border p-5',
        graded.correct ? 'border-ok/40 bg-ok-bg' : 'border-bad/40 bg-bad-bg',
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex gap-3">
          <span className="tnum shrink-0 font-mono text-[11px] leading-6 text-ink-muted">
            {String(graded.index + 1).padStart(2, '0')}
          </span>
          <p className="m-0 font-medium text-ink">{graded.prompt}</p>
        </div>
        <Pill tone={graded.correct ? 'ok' : 'bad'}>{graded.correct ? 'Correct' : 'Wrong'}</Pill>
      </div>

      {graded.type === 'MULTIPLE_CHOICE' ? (
        <div className="mt-4 flex flex-col gap-1.5 pl-8">
          {graded.options.map((option, optionIndex) => {
            const isCorrect = graded.correctOptionIndex === optionIndex
            const isPicked = graded.selectedOptionIndex === optionIndex
            return (
              <div
                key={optionIndex}
                className={cx(
                  'flex items-center gap-2 rounded-ctl border border-l-2 px-3 py-2 text-sm',
                  isCorrect
                    ? 'border-ok/40 border-l-ok text-ink'
                    : isPicked
                      ? 'border-bad/40 border-l-bad text-ink'
                      : 'border-line-soft border-l-transparent text-ink-2',
                )}
              >
                <span>{option}</span>
                {isCorrect && (
                  <span className="ml-auto font-mono text-[10.5px] tracking-[0.06em] text-ok uppercase">
                    ✓ answer
                  </span>
                )}
                {isPicked && !isCorrect && (
                  <span className="ml-auto font-mono text-[10.5px] tracking-[0.06em] text-bad uppercase">
                    your pick
                  </span>
                )}
              </div>
            )
          })}
        </div>
      ) : (
        <div className="mt-4 flex flex-col gap-2 pl-8 text-sm">
          <div>
            <Eyebrow>Your answer</Eyebrow>
            <p className="m-0 text-ink">
              {graded.answerText?.trim() ? graded.answerText : '— (blank)'}
            </p>
          </div>
          {graded.correctAnswer && (
            <div>
              <Eyebrow>Expected</Eyebrow>
              <p className="m-0 text-ink">{graded.correctAnswer}</p>
            </div>
          )}
        </div>
      )}

      {graded.explanation && (
        <p className="mt-4 ml-8 border-t border-line-soft pt-3 text-sm text-ink-2">
          {graded.explanation}
        </p>
      )}
    </li>
  )
}
