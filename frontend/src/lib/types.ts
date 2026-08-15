// Mirrors the backend DTOs (com.studyloop.backend.auth.dto). Kept hand-written and small
// rather than generated — the surface is tiny and this keeps the frontend build dependency-free.

export type Role = 'USER' | 'ADMIN'

export interface UserResponse {
  id: string
  email: string
  displayName: string
  role: Role
  createdAt: string
}

export interface TokenResponse {
  accessToken: string
  refreshToken: string
  user: UserResponse
}

export interface RegisterRequest {
  email: string
  password: string
  displayName: string
}

export interface LoginRequest {
  email: string
  password: string
}

// --- Courses (com.studyloop.backend.course.dto) ---

export type MembershipRole = 'OWNER' | 'INSTRUCTOR' | 'MEMBER'

export interface CourseResponse {
  id: string
  name: string
  description: string | null
  ownerId: string
  myRole: MembershipRole
  createdAt: string
}

export interface CreateCourseRequest {
  name: string
  description?: string
}

// The generic paging envelope the backend wraps list endpoints in (common/PageResponse).
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface InvitePreviewResponse {
  courseId: string
  courseName: string
  role: MembershipRole
  requiresMatchingEmail: boolean
}

// --- Documents (com.studyloop.backend.document.dto) ---

// Ingestion lifecycle. The terminal states are READY and FAILED; everything else is in
// flight and worth polling.
export type DocumentStatus =
  | 'UPLOADED'
  | 'EXTRACTING'
  | 'CHUNKING'
  | 'EMBEDDING'
  | 'READY'
  | 'FAILED'

export interface DocumentResponse {
  id: string
  courseId: string
  filename: string
  contentType: string
  sizeBytes: number
  sha256: string
  status: DocumentStatus
  // Present only when status is FAILED.
  errorMessage: string | null
  pageCount: number | null
  uploadedById: string
  createdAt: string
  updatedAt: string
}

// One entry in a document's auto-generated glossary.
export interface GlossaryTerm {
  term: string
  definition: string
}

// A document's cached AI summary. `summary` is null and `terms` empty when it hasn't been
// generated yet — an ordinary state, not an error.
export interface DocumentSummary {
  documentId: string
  filename: string
  status: DocumentStatus
  summary: string | null
  terms: GlossaryTerm[]
  generatedAt: string | null
}

// --- Chat (com.studyloop.backend.chat.dto) ---

// One numbered source an answer was grounded on. `index` is the [n] marker the model writes
// inline; the viewer maps it to a document + page.
export interface Citation {
  index: number
  chunkId: string
  documentId: string
  filename: string
  pageNumber: number | null
  snippet: string
}

// The non-streaming chat reply. The streaming endpoint delivers the same pieces as SSE events
// (see ChatStreamEvents below) rather than one body.
export interface ChatResponse {
  conversationId: string
  answer: string
  citations: Citation[]
}

// Payloads of the SSE events the /chat/stream endpoint emits, keyed by event name.
export interface ChatMetaEvent {
  conversationId: string
  citations: Citation[]
}

export interface ChatDoneEvent {
  conversationId: string
}

// --- Quizzes (com.studyloop.backend.quiz.dto) ---

export type QuestionType = 'MULTIPLE_CHOICE' | 'SHORT_ANSWER'

export interface QuizSummary {
  id: string
  title: string
  questionCount: number
  createdAt: string
}

// One question in the take view — no answer key (options is empty for short-answer questions).
export interface QuizQuestionView {
  id: string
  index: number
  type: QuestionType
  prompt: string
  options: string[]
}

export interface Quiz {
  id: string
  title: string
  createdAt: string
  questions: QuizQuestionView[]
}

export interface GenerateQuizRequest {
  documentIds?: string[] | null
  multipleChoiceCount?: number
  shortAnswerCount?: number
  title?: string
}

// One submitted answer: set selectedOptionIndex for multiple-choice, answerText for short-answer.
export interface AnswerInput {
  questionId: string
  selectedOptionIndex?: number | null
  answerText?: string | null
}

export interface SubmitAttemptRequest {
  answers: AnswerInput[]
}

// A graded question: what the user gave plus the revealed answer key and explanation.
export interface GradedAnswer {
  questionId: string
  index: number
  type: QuestionType
  prompt: string
  options: string[]
  selectedOptionIndex: number | null
  answerText: string | null
  correct: boolean
  correctOptionIndex: number | null
  correctAnswer: string | null
  explanation: string | null
}

export interface AttemptResponse {
  attemptId: string
  quizId: string
  score: number
  total: number
  createdAt: string
  answers: GradedAnswer[]
  // How many missed questions became new review cards (0 on a perfect score, and 0 on a
  // retake where those questions already have cards).
  cardsEnrolled: number
}

export interface AttemptSummary {
  attemptId: string
  score: number
  total: number
  createdAt: string
}

// --- Flashcards (com.studyloop.backend.flashcard.dto) ---

export interface Flashcard {
  id: string
  front: string
  back: string
  sourceDocumentId: string | null
  sourcePage: number | null
  createdAt: string
}

export interface GenerateFlashcardsRequest {
  documentId: string
  count?: number
}

export interface CreateFlashcardRequest {
  front: string
  back: string
  sourceDocumentId?: string | null
  sourcePage?: number | null
}

// --- Cost dashboard (com.studyloop.backend.usage.dto) ---

export interface CostTotals {
  calls: number
  inputTokens: number
  outputTokens: number
  costUsd: number
}

// One feature's share of the bill. `operation` is the backend AiOperation enum name.
export interface OperationCost {
  operation: string
  calls: number
  inputTokens: number
  outputTokens: number
  costUsd: number
}

export interface DailyCost {
  day: string
  calls: number
  costUsd: number
}

// The semantic cache's scoreboard. Cumulative, unlike the spend figures — an entry knows how
// often it has been reused but not when, so there is no window to apply.
export interface CacheStats {
  entries: number
  hits: number
  answersFromModel: number
  hitRate: number
  estimatedSavedUsd: number
}

export interface CostSummary {
  windowDays: number
  totals: CostTotals
  byOperation: OperationCost[]
  daily: DailyCost[]
  cache: CacheStats
}

// --- Spaced repetition (com.studyloop.backend.review.dto) ---

// A card that is due, with enough of its SM-2 state to show how well it's known.
export interface ReviewCard {
  cardId: string
  courseId: string
  courseName: string
  front: string
  back: string
  sourceDocumentId: string | null
  sourcePage: number | null
  dueOn: string
  intervalDays: number
  repetitions: number
  lapses: number
  easeFactor: number
}

// What grading a card did to its schedule. `lapsed` means the grade was below 3, so the card
// restarted and comes back tomorrow.
export interface ReviewResult {
  cardId: string
  grade: number
  lapsed: boolean
  dueOn: string
  intervalDays: number
  repetitions: number
  lapses: number
  easeFactor: number
  remainingToday: number
}
