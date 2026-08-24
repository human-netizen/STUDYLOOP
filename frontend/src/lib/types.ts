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

// What language a document's text is written in (Phase 19.1). Two values because the backend
// detects two scripts — it is a fact about the encoding rather than a guess, and a wider union
// would be claiming a precision nothing produces.
export type DocumentLanguage = 'ENGLISH' | 'BANGLA'

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
  // ENGLISH until extraction has run, and ENGLISH afterwards for everything the detector found no
  // Bengali script in — never null and never unknown.
  language: DocumentLanguage
  uploadedById: string
  createdAt: string
  updatedAt: string
}

// Whether a document is the course's or one member's (Phase 16.3). A photographed note starts
// OWNER and becomes COURSE only when a manager promotes it.
export type DocumentVisibility = 'OWNER' | 'COURSE'

// A digitised handwritten note. Deliberately not a DocumentResponse even though it is the same
// row: what a note's list needs is whether it is still private, whether it is yours, and whether
// it has finished being read — and `mine` cannot be computed here, since the caller's own id is
// not in the payload.
export interface NoteResponse {
  id: string
  courseId: string
  filename: string
  status: DocumentStatus
  // Present only when status is FAILED — usually "the photo was too blurred to read".
  errorMessage: string | null
  visibility: DocumentVisibility
  mine: boolean
  uploadedById: string
  createdAt: string
  updatedAt: string
}

// One block a vision model read off a photographed page. `indexed` is the field the review screen
// is built around: false means the model read this text and was not confident enough about it to
// put it in the course's index — so it is shown, and it answers nothing.
export interface NoteBlock {
  ordinal: number
  content: string
  confidence: number
  indexed: boolean
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
//
// `documentSource` says whether there is a file behind it. UPLOAD opens in the PDF viewer; FORUM
// is an accepted forum answer written back into the corpus, which has no file and no page. Older
// cached answers predate the field, so treat anything that isn't 'FORUM' as openable.
export interface Citation {
  index: number
  chunkId: string
  documentId: string
  filename: string
  documentSource: DocumentSourceKind | null
  pageNumber: number | null
  // True when retrieval found this page by looking at it rather than by reading it (Phase 17).
  // It is a different promise from an ordinary citation — the answer is in the picture on that
  // page, not in a sentence to be found on it — so the link says so. Older cached answers predate
  // the field and arrive undefined, which reads as false.
  visual?: boolean
  snippet: string
}

export type DocumentSourceKind = 'UPLOAD' | 'FORUM'

// The non-streaming chat reply. The streaming endpoint delivers the same pieces as SSE events
// (see ChatStreamEvents below) rather than one body.
export interface ChatResponse {
  conversationId: string
  answer: string
  citations: Citation[]
  questionEventId: string | null
  askedBefore: AskedBefore | null
}

// "You've asked about this before" (Phase 20.3). Set only when this student has asked this course
// the same thing at least twice already. There is no previous *answer* here on purpose: the server
// sends a count and their own earlier wording, never what it told them last time.
export interface AskedBefore {
  times: number
  lastAskedAt: string
  lastQuestion: string
}

// An answer given from general knowledge after the course could not answer (Phase 20.2). No
// citations field at all, rather than an empty one — this shape cannot be rendered as a grounded
// answer by accident.
export interface GeneralAnswerResponse {
  conversationId: string
  answer: string
}

// Payloads of the SSE events the /chat/stream endpoint emits, keyed by event name.
//
// `questionEventId` is set only when the confidence gate refused — it is the handle for turning
// that refusal into a forum thread about this exact question.
export interface ChatMetaEvent {
  conversationId: string
  citations: Citation[]
  questionEventId: string | null
  askedBefore: AskedBefore | null
}

export interface ChatDoneEvent {
  conversationId: string
}

// --- Search (com.studyloop.backend.retrieval.dto) ---

// A snippet arrives pre-split: `match` runs are the words the query matched. The split is done on
// the server so the matching rule (which counts "indexing" as a match for "index") lives in one
// place rather than being re-implemented here and drifting.
export interface SnippetPart {
  text: string
  match: boolean
}

// One passage. `similarity` is the raw cosine against the query — the same number the assistant's
// confidence gate reads — and is null when the passage was found lexically only.
export interface SearchHit {
  chunkId: string
  pageNumber: number | null
  similarity: number | null
  score: number
  snippet: SnippetPart[]
}

// Every hit inside one document, so a lecture that matched five times is one result.
export interface SearchDocument {
  documentId: string
  filename: string
  source: DocumentSourceKind
  hitCount: number
  hits: SearchHit[]
}

export interface SearchResponse {
  query: string
  hitCount: number
  documents: SearchDocument[]
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

// --- Confusion analytics (com.studyloop.backend.analytics.dto) ---

export interface ConfusionTotals {
  questionsAsked: number
  ungrounded: number
  ungroundedRate: number
  // How many refusals a student then asked from general knowledge (Phase 20.2) — a sharper signal
  // about missing material than the refusal count, which includes every off-topic question anyone
  // ever typed.
  escalatedToGeneral: number
  distinctAskers: number
}

// One row of the heatmap. `share` is this lecture's fraction of all lecture-attributed
// questions — what the bar length encodes.
export interface LectureHeat {
  documentId: string
  filename: string
  questionCount: number
  distinctAskers: number
  share: number
  lastAskedAt: string
}

export interface TopicLecture {
  documentId: string
  filename: string
  questionCount: number
}

// A group of questions that mean roughly the same thing. `label` is a real student question —
// the one closest to the group's centre — not a generated topic name.
export interface TopicCluster {
  label: string
  questionCount: number
  ungroundedCount: number
  distinctAskers: number
  lectures: TopicLecture[]
  lastAskedAt: string
}

// A question the confidence gate refused. No asker: the backend never sends one. `threadId` is
// the forum discussion it was escalated into, or null while nobody has escalated it.
export interface UngroundedQuestion {
  question: string
  topSimilarity: number | null
  askedAt: string
  questionEventId: string
  threadId: string | null
  escalatedToGeneral: boolean
}

export interface ConfusionReport {
  windowDays: number
  totals: ConfusionTotals
  lectures: LectureHeat[]
  topics: TopicCluster[]
  ungrounded: UngroundedQuestion[]
  clustersComputedAt: string | null
}

// --- Forum (com.studyloop.backend.forum.dto) ---

export type ForumThreadStatus = 'OPEN' | 'ANSWERED'

// One reply. Names are on the wire here, unlike the confusion report — that page is a
// measurement of the class, this is a conversation.
export type ForumAnswerAuthor = 'MEMBER' | 'ASSISTANT'

export interface ForumAnswer {
  id: string
  body: string
  // ASSISTANT replies are posted by the corpus watch when an upload makes the thread answerable
  // (Phase 20.1). They carry no author name, cannot be accepted into the materials, and say which
  // document made them possible.
  authorKind: ForumAnswerAuthor
  authorName: string | null
  accepted: boolean
  sourceDocumentId: string | null
  topSimilarity: number | null
  createdAt: string
}

// A row in the forum list. `inCorpus` means the accepted answer is now material the assistant can
// retrieve; `fromRefusal` means the thread came from a question chat couldn't answer.
export interface ForumThreadSummary {
  id: string
  title: string
  status: ForumThreadStatus
  authorName: string
  answerCount: number
  inCorpus: boolean
  fromRefusal: boolean
  // The assistant has replied here since the thread was opened (Phase 20.1) — a reply waiting for
  // somebody to confirm or correct it, not a resolution.
  assistantAnswered: boolean
  createdAt: string
  updatedAt: string
}

// `canAccept` is decided by the backend rather than inferred from a role here: accepting writes
// into the course's corpus, so the button should appear for exactly the people it will let through.
export interface ForumThreadDetail {
  id: string
  title: string
  body: string | null
  status: ForumThreadStatus
  authorName: string
  questionEventId: string | null
  acceptedAnswerId: string | null
  inCorpus: boolean
  canAccept: boolean
  answers: ForumAnswer[]
  createdAt: string
  updatedAt: string
}

export interface CreateThreadRequest {
  title: string
  body?: string | null
  questionEventId?: string | null
}

// ── Phase 21 · video generation ─────────────────────────────────────────────────────────────

// The same shape as DocumentStatus, and the page polls it the same way. QUEUED means a render is
// already running for somebody — the slot is one deep on purpose. REFUSED is not a failure: the
// corpus could not support the topic and the gate said so before anything was rendered.
export type VideoJobStatus =
  | 'QUEUED'
  | 'PLANNING'
  | 'RENDERING'
  | 'COMPOSING'
  | 'READY'
  | 'FAILED'
  | 'REFUSED'

// What the scene turned out to be, never what was planned for it.
export type SceneRendering = 'ANIMATED' | 'SLIDE'

export interface VideoScene {
  index: number
  title: string
  // Also this scene's caption text, so the rail can show what is being said without the video
  // being played.
  narration: string
  renderedAs: SceneRendering
  // Present only when an animation was attempted and lost: the sandbox layer that stopped it and
  // what the toolchain said. Shown rather than swallowed — a fallback nobody can see is the exact
  // defect this feature was rejected for in the first place.
  fallbackReason: string | null
  durationSeconds: number | null
  citations: Citation[]
}

export interface VideoJob {
  id: string
  courseId: string
  topic: string
  language: DocumentLanguage
  status: VideoJobStatus
  // The sentence under the progress bar — "rendering scene 3 of 6".
  stage: string | null
  scenesTotal: number
  scenesAnimated: number
  scenesFallback: number
  durationSeconds: number | null
  hasCaptions: boolean
  // The reason it stopped. For REFUSED this is the confidence gate speaking, and the page renders
  // it with the same two buttons a refused chat answer gets.
  error: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  scenes: VideoScene[]
}

// One call answers both questions the page has: can this installation make videos, and what has
// this member already asked for. Together, so a button cannot be drawn from a flag fetched at a
// different moment than the jobs.
export interface VideoLibrary {
  enabled: boolean
  // Separate from `enabled` because they are different sentences: one means this installation does
  // not do videos, the other means the renderer is not running right now — and only the second one
  // is something the person reading it can fix.
  workerReachable: boolean
  dailyCap: number
  usedToday: number
  jobs: VideoJob[]
}
