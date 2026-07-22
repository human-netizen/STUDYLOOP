import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError, quizzesApi } from '../lib/api'
import type { AnswerInput, AttemptResponse, GradedAnswer, Quiz, QuizQuestionView } from '../lib/types'
import { AppHeader } from '../components/AppHeader'

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

  return (
    <div className="min-h-screen bg-slate-50 dark:bg-slate-950">
      <AppHeader />
      <main className="mx-auto max-w-3xl px-6 py-10">
        <Link
          to={`/courses/${id}/quizzes`}
          className="text-sm text-slate-500 hover:underline dark:text-slate-400"
        >
          ← Quizzes
        </Link>

        {loading && <p className="mt-6 text-slate-500 dark:text-slate-400">Loading…</p>}
        {error && <p className="mt-6 text-sm text-red-500">{error}</p>}

        {quiz && (
          <>
            <h1 className="mt-4 text-2xl font-semibold tracking-tight text-slate-900 dark:text-slate-100">
              {quiz.title}
            </h1>

            {result && <ScoreBanner score={result.score} total={result.total} onRetake={retake} />}

            <ol className="mt-8 flex flex-col gap-6">
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
              <div className="mt-8">
                <button
                  type="button"
                  onClick={() => void submit()}
                  disabled={submitting}
                  className="rounded-xl bg-indigo-600 px-5 py-2.5 text-sm font-medium text-white transition hover:bg-indigo-500 disabled:opacity-60"
                >
                  {submitting ? 'Grading…' : 'Submit answers'}
                </button>
                {submitError && <p className="mt-3 text-sm text-red-500">{submitError}</p>}
              </div>
            )}
          </>
        )}
      </main>
    </div>
  )
}

function ScoreBanner({ score, total, onRetake }: { score: number; total: number; onRetake: () => void }) {
  const pct = total === 0 ? 0 : Math.round((score / total) * 100)
  return (
    <div className="mt-6 flex items-center justify-between gap-4 rounded-xl border border-indigo-200 bg-indigo-50 p-5 dark:border-indigo-900 dark:bg-indigo-950/40">
      <div>
        <p className="text-sm text-indigo-600 dark:text-indigo-300">Your score</p>
        <p className="text-2xl font-semibold text-slate-900 dark:text-slate-100">
          {score} / {total} <span className="text-base font-normal text-slate-500">({pct}%)</span>
        </p>
      </div>
      <button
        type="button"
        onClick={onRetake}
        className="rounded-xl border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 transition hover:bg-white dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800"
      >
        Retake
      </button>
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
    <li className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
      <p className="font-medium text-slate-900 dark:text-slate-100">
        <span className="mr-2 text-slate-400">{question.index + 1}.</span>
        {question.prompt}
      </p>

      {question.type === 'MULTIPLE_CHOICE' ? (
        <div className="mt-4 flex flex-col gap-2">
          {question.options.map((option, optionIndex) => {
            const selected = answer?.selectedOptionIndex === optionIndex
            return (
              <label
                key={optionIndex}
                className={[
                  'flex cursor-pointer items-center gap-3 rounded-lg border px-3 py-2 text-sm transition',
                  selected
                    ? 'border-indigo-500 bg-indigo-50 dark:bg-indigo-950/30'
                    : 'border-slate-200 hover:border-indigo-300 dark:border-slate-700',
                ].join(' ')}
              >
                <input
                  type="radio"
                  name={question.id}
                  checked={selected}
                  disabled={disabled}
                  onChange={() => onOption(question.id, optionIndex)}
                  className="accent-indigo-600"
                />
                <span className="text-slate-700 dark:text-slate-200">{option}</span>
              </label>
            )
          })}
        </div>
      ) : (
        <textarea
          value={answer?.answerText ?? ''}
          disabled={disabled}
          onChange={(event) => onText(question.id, event.target.value)}
          rows={3}
          placeholder="Your answer…"
          className="mt-4 w-full resize-y rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100"
        />
      )}
    </li>
  )
}

function ReviewQuestion({ graded }: { graded: GradedAnswer }) {
  return (
    <li
      className={[
        'rounded-xl border p-5',
        graded.correct
          ? 'border-emerald-200 bg-emerald-50 dark:border-emerald-900 dark:bg-emerald-950/30'
          : 'border-red-200 bg-red-50 dark:border-red-900 dark:bg-red-950/30',
      ].join(' ')}
    >
      <div className="flex items-start justify-between gap-3">
        <p className="font-medium text-slate-900 dark:text-slate-100">
          <span className="mr-2 text-slate-400">{graded.index + 1}.</span>
          {graded.prompt}
        </p>
        <span
          className={[
            'shrink-0 rounded-full px-2.5 py-0.5 text-xs font-medium',
            graded.correct
              ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/50 dark:text-emerald-300'
              : 'bg-red-100 text-red-700 dark:bg-red-900/50 dark:text-red-300',
          ].join(' ')}
        >
          {graded.correct ? 'Correct' : 'Incorrect'}
        </span>
      </div>

      {graded.type === 'MULTIPLE_CHOICE' ? (
        <div className="mt-4 flex flex-col gap-2">
          {graded.options.map((option, optionIndex) => {
            const isCorrect = graded.correctOptionIndex === optionIndex
            const isPicked = graded.selectedOptionIndex === optionIndex
            return (
              <div
                key={optionIndex}
                className={[
                  'flex items-center gap-2 rounded-lg border px-3 py-2 text-sm',
                  isCorrect
                    ? 'border-emerald-400 bg-emerald-100/60 dark:border-emerald-700 dark:bg-emerald-900/30'
                    : isPicked
                      ? 'border-red-400 bg-red-100/60 dark:border-red-700 dark:bg-red-900/30'
                      : 'border-slate-200 dark:border-slate-700',
                ].join(' ')}
              >
                <span className="text-slate-700 dark:text-slate-200">{option}</span>
                {isCorrect && <span className="ml-auto text-xs font-medium text-emerald-600">✓ correct</span>}
                {isPicked && !isCorrect && (
                  <span className="ml-auto text-xs font-medium text-red-600">your pick</span>
                )}
              </div>
            )
          })}
        </div>
      ) : (
        <div className="mt-4 space-y-2 text-sm">
          <p className="text-slate-500 dark:text-slate-400">
            Your answer:{' '}
            <span className="text-slate-800 dark:text-slate-200">
              {graded.answerText?.trim() ? graded.answerText : '— (blank)'}
            </span>
          </p>
          {graded.correctAnswer && (
            <p className="text-slate-500 dark:text-slate-400">
              Expected: <span className="text-slate-800 dark:text-slate-200">{graded.correctAnswer}</span>
            </p>
          )}
        </div>
      )}

      {graded.explanation && (
        <p className="mt-4 border-t border-slate-200/70 pt-3 text-sm text-slate-600 dark:border-slate-700/70 dark:text-slate-300">
          {graded.explanation}
        </p>
      )}
    </li>
  )
}
