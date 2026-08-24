import type {
  AttemptResponse,
  AttemptSummary,
  ChatDoneEvent,
  ChatMetaEvent,
  ConfusionReport,
  CostSummary,
  CourseResponse,
  CreateCourseRequest,
  CreateFlashcardRequest,
  CreateThreadRequest,
  DocumentResponse,
  DocumentSummary,
  Flashcard,
  ForumThreadDetail,
  ForumThreadStatus,
  ForumThreadSummary,
  GeneralAnswerResponse,
  GenerateFlashcardsRequest,
  GenerateQuizRequest,
  InvitePreviewResponse,
  LoginRequest,
  NoteBlock,
  NoteResponse,
  PageResponse,
  Quiz,
  QuizSummary,
  RegisterRequest,
  ReviewCard,
  ReviewResult,
  SearchResponse,
  SubmitAttemptRequest,
  TokenResponse,
  UserResponse,
  VideoJob,
  VideoLibrary,
} from './types'

// The backend origin. Override with VITE_API_URL (e.g. in production); defaults to the
// local Spring Boot server, whose CORS already allows the Vite dev origin.
const ORIGIN = (import.meta.env.VITE_API_URL as string | undefined) ?? 'http://localhost:8080'
export const API_BASE = `${ORIGIN}/api/v1`

const ACCESS_KEY = 'studyloop.accessToken'
const REFRESH_KEY = 'studyloop.refreshToken'

// Tokens live in localStorage so a page refresh keeps the session. (A future hardening pass
// could move the refresh token to an httpOnly cookie; fine for the MVP.)
export const tokenStore = {
  getAccess: () => localStorage.getItem(ACCESS_KEY),
  getRefresh: () => localStorage.getItem(REFRESH_KEY),
  set(access: string, refresh: string) {
    localStorage.setItem(ACCESS_KEY, access)
    localStorage.setItem(REFRESH_KEY, refresh)
  },
  clear() {
    localStorage.removeItem(ACCESS_KEY)
    localStorage.removeItem(REFRESH_KEY)
  },
}

// Thrown for any non-2xx response. `message` comes from the RFC 7807 ProblemDetail the
// backend returns; `fieldErrors` carries per-field validation messages when present.
// `reason` and `retryAfterSeconds` are set on the 429s the quota guards produce.
export class ApiError extends Error {
  readonly status: number
  readonly fieldErrors?: Record<string, string>
  readonly reason?: string
  readonly retryAfterSeconds?: number

  constructor(
    status: number,
    message: string,
    fieldErrors?: Record<string, string>,
    reason?: string,
    retryAfterSeconds?: number,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
    this.reason = reason
    this.retryAfterSeconds = retryAfterSeconds
  }
}

async function toError(res: Response): Promise<ApiError> {
  let message = res.statusText
  let fieldErrors: Record<string, string> | undefined
  let reason: string | undefined
  let retryAfterSeconds: number | undefined
  try {
    const body = await res.json()
    message = body.detail ?? body.title ?? message
    if (body.errors) fieldErrors = body.errors as Record<string, string>
    if (typeof body.reason === 'string') reason = body.reason
    if (typeof body.retryAfterSeconds === 'number') retryAfterSeconds = body.retryAfterSeconds
  } catch {
    // non-JSON body — keep the status text
  }
  return new ApiError(res.status, message, fieldErrors, reason, retryAfterSeconds)
}

// The message to put in front of someone whose request was refused, with the wait folded in.
// "Too many requests" and "you're out of allowance until tomorrow" both arrive as 429 and need
// very different reactions from the reader, so the two are never collapsed into one sentence.
export function errorMessage(err: unknown, fallback: string): string {
  if (!(err instanceof ApiError)) return fallback
  if (err.status !== 429 || err.retryAfterSeconds == null) return err.message
  return `${err.message} Try again in ${humanWait(err.retryAfterSeconds)}.`
}

function humanWait(seconds: number): string {
  if (seconds < 60) return `${Math.max(1, Math.round(seconds))} seconds`
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return minutes === 1 ? 'a minute' : `${minutes} minutes`
  const hours = Math.round(seconds / 3600)
  return hours === 1 ? 'an hour' : `${hours} hours`
}

interface RequestOptions {
  method?: string
  body?: unknown
  auth?: boolean
}

function send(path: string, options: RequestOptions, token: string | null): Promise<Response> {
  const headers: Record<string, string> = {}
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers['Authorization'] = `Bearer ${token}`
  return fetch(API_BASE + path, {
    method: options.method ?? 'GET',
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  })
}

// Swap an expired access token for a fresh pair using the refresh token. Returns false (and
// clears storage) if the refresh token is missing or rejected.
async function tryRefresh(): Promise<boolean> {
  const refreshToken = tokenStore.getRefresh()
  if (!refreshToken) return false
  const res = await send('/auth/refresh', { method: 'POST', body: { refreshToken } }, null)
  if (!res.ok) {
    tokenStore.clear()
    return false
  }
  const data = (await res.json()) as TokenResponse
  tokenStore.set(data.accessToken, data.refreshToken)
  return true
}

// Core request helper. For authed calls it attaches the access token and, on a single 401,
// transparently refreshes once and retries before giving up.
async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const authed = options.auth ?? false
  let res = await send(path, options, authed ? tokenStore.getAccess() : null)

  if (res.status === 401 && authed && (await tryRefresh())) {
    res = await send(path, options, tokenStore.getAccess())
  }

  if (!res.ok) throw await toError(res)
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

// Multipart upload helper. Kept separate from `send` because the browser must set the
// multipart Content-Type (with its boundary) itself — we only attach auth. Mirrors the
// single-retry-on-401 refresh behaviour of `request`.
function sendUpload(path: string, form: FormData, token: string | null): Promise<Response> {
  const headers: Record<string, string> = {}
  if (token) headers['Authorization'] = `Bearer ${token}`
  return fetch(API_BASE + path, { method: 'POST', headers, body: form })
}

async function upload<T>(path: string, form: FormData): Promise<T> {
  let res = await sendUpload(path, form, tokenStore.getAccess())
  if (res.status === 401 && (await tryRefresh())) {
    res = await sendUpload(path, form, tokenStore.getAccess())
  }
  if (!res.ok) throw await toError(res)
  return (await res.json()) as T
}

export const authApi = {
  register: (body: RegisterRequest) =>
    request<UserResponse>('/auth/register', { method: 'POST', body }),
  login: (body: LoginRequest) =>
    request<TokenResponse>('/auth/login', { method: 'POST', body }),
  me: () => request<UserResponse>('/users/me', { auth: true }),
}

export const coursesApi = {
  list: (page = 0, size = 20) =>
    request<PageResponse<CourseResponse>>(`/courses?page=${page}&size=${size}`, { auth: true }),
  create: (body: CreateCourseRequest) =>
    request<CourseResponse>('/courses', { method: 'POST', body, auth: true }),
  get: (id: string) => request<CourseResponse>(`/courses/${id}`, { auth: true }),
}

export const documentsApi = {
  list: (courseId: string) =>
    request<DocumentResponse[]>(`/courses/${courseId}/documents`, { auth: true }),
  get: (courseId: string, documentId: string) =>
    request<DocumentResponse>(`/courses/${courseId}/documents/${documentId}`, { auth: true }),
  upload: (courseId: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return upload<DocumentResponse>(`/courses/${courseId}/documents`, form)
  },
  // The cached summary + glossary. Cheap — it never triggers generation, so `summary` comes
  // back null for a document that hasn't been summarized yet.
  summary: (courseId: string, documentId: string) =>
    request<DocumentSummary>(`/courses/${courseId}/documents/${documentId}/summary`, {
      auth: true,
    }),
  // Generates it on demand. Idempotent unless `refresh` is set, so this is safe to call for a
  // document whose summary may already exist.
  generateSummary: (courseId: string, documentId: string, refresh = false) =>
    request<DocumentSummary>(
      `/courses/${courseId}/documents/${documentId}/summary${refresh ? '?refresh=true' : ''}`,
      { method: 'POST', auth: true },
    ),
  // Fetches the raw PDF bytes (auth-guarded) as a Blob for client-side rendering. The caller
  // turns it into an object URL.
  fileBlob: (courseId: string, documentId: string) =>
    fetchBlob(`/courses/${courseId}/documents/${documentId}/file`),
}

// Any authenticated endpoint that answers with bytes rather than JSON — a PDF, a LaTeX export, an
// mp4, a caption track. Same single-retry-on-401 refresh as `request`; extracted when Phase 21
// made it the third caller, because three copies of a token refresh is three places for it to
// drift.
export async function fetchBlob(path: string): Promise<Blob> {
  const get = (token: string | null) =>
    fetch(API_BASE + path, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
  let res = await get(tokenStore.getAccess())
  if (res.status === 401 && (await tryRefresh())) {
    res = await get(tokenStore.getAccess())
  }
  if (!res.ok) throw await toError(res)
  return res.blob()
}

// Digitised handwritten notes (Phase 16.3). A separate surface from documents, because the rules
// differ on every axis: any member may add one, only images are accepted, only its owner (and a
// manager reviewing it) may read it, and only a manager may make it part of the course's corpus.
export const notesApi = {
  // Your own notes, plus every note this course has promoted. Never anybody else's private ones —
  // a list that showed more than search does would disclose that a note exists.
  list: (courseId: string) =>
    request<NoteResponse[]>(`/courses/${courseId}/notes`, { auth: true }),
  upload: (courseId: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return upload<DocumentResponse>(`/courses/${courseId}/notes`, form)
  },
  // The review view: every block the model read, kept or dropped, with the confidence it reported.
  blocks: (courseId: string, noteId: string) =>
    request<NoteBlock[]>(`/courses/${courseId}/notes/${noteId}/blocks`, { auth: true }),
  promote: (courseId: string, noteId: string) =>
    request<NoteResponse>(`/courses/${courseId}/notes/${noteId}/promote`, {
      method: 'POST',
      auth: true,
    }),
  demote: (courseId: string, noteId: string) =>
    request<NoteResponse>(`/courses/${courseId}/notes/${noteId}/demote`, {
      method: 'POST',
      auth: true,
    }),
  // The note as a LaTeX document, carrying only the blocks that were confident enough to index.
  // Fetched with the bearer token and turned into an object URL by the caller, the same way the
  // PDF viewer fetches a document's bytes — a plain <a href> would arrive unauthenticated.
  latex: (courseId: string, noteId: string) =>
    fetchBlob(`/courses/${courseId}/notes/${noteId}/latex`),
}

export const quizzesApi = {
  list: (courseId: string) =>
    request<QuizSummary[]>(`/courses/${courseId}/quizzes`, { auth: true }),
  get: (courseId: string, quizId: string) =>
    request<Quiz>(`/courses/${courseId}/quizzes/${quizId}`, { auth: true }),
  generate: (courseId: string, body: GenerateQuizRequest) =>
    request<Quiz>(`/courses/${courseId}/quizzes`, { method: 'POST', body, auth: true }),
  submit: (courseId: string, quizId: string, body: SubmitAttemptRequest) =>
    request<AttemptResponse>(`/courses/${courseId}/quizzes/${quizId}/attempts`, {
      method: 'POST',
      body,
      auth: true,
    }),
  attempts: (courseId: string, quizId: string) =>
    request<AttemptSummary[]>(`/courses/${courseId}/quizzes/${quizId}/attempts`, { auth: true }),
}

export const flashcardsApi = {
  list: (courseId: string) =>
    request<Flashcard[]>(`/courses/${courseId}/flashcards`, { auth: true }),
  generate: (courseId: string, body: GenerateFlashcardsRequest) =>
    request<Flashcard[]>(`/courses/${courseId}/flashcards/generate`, {
      method: 'POST',
      body,
      auth: true,
    }),
  create: (courseId: string, body: CreateFlashcardRequest) =>
    request<Flashcard>(`/courses/${courseId}/flashcards`, { method: 'POST', body, auth: true }),
  remove: (courseId: string, cardId: string) =>
    request<void>(`/courses/${courseId}/flashcards/${cardId}`, { method: 'DELETE', auth: true }),
}

// The review queue spans every course, so these hang off /review rather than /courses/{id}.
// Pass courseId to narrow the queue to one course.
export const reviewApi = {
  queue: (courseId?: string) =>
    request<ReviewCard[]>(`/review/queue${courseId ? `?courseId=${courseId}` : ''}`, { auth: true }),
  dueCount: (courseId?: string) =>
    request<{ due: number }>(`/review/due-count${courseId ? `?courseId=${courseId}` : ''}`, {
      auth: true,
    }),
  grade: (cardId: string, grade: number) =>
    request<ReviewResult>(`/review/${cardId}`, { method: 'POST', body: { grade }, auth: true }),
}

// The course forum. Every member may read, ask and answer; only managers may accept an answer,
// because accepting writes it into the course's corpus — the detail response says which you are
// via `canAccept`, so the UI never has to guess.
export const forumApi = {
  list: (courseId: string, status?: ForumThreadStatus) =>
    request<ForumThreadSummary[]>(
      `/courses/${courseId}/forum/threads${status ? `?status=${status}` : ''}`,
      { auth: true },
    ),
  get: (courseId: string, threadId: string) =>
    request<ForumThreadDetail>(`/courses/${courseId}/forum/threads/${threadId}`, { auth: true }),
  // Escalating the same refusal twice returns the thread that already exists, so this is safe to
  // call from a button somebody double-clicks.
  open: (courseId: string, body: CreateThreadRequest) =>
    request<ForumThreadDetail>(`/courses/${courseId}/forum/threads`, {
      method: 'POST',
      body,
      auth: true,
    }),
  answer: (courseId: string, threadId: string, body: string) =>
    request<ForumThreadDetail>(`/courses/${courseId}/forum/threads/${threadId}/answers`, {
      method: 'POST',
      body: { body },
      auth: true,
    }),
  accept: (courseId: string, threadId: string, answerId: string) =>
    request<ForumThreadDetail>(
      `/courses/${courseId}/forum/threads/${threadId}/answers/${answerId}/accept`,
      { method: 'POST', auth: true },
    ),
}

// Search a course's materials. The same retrieval the assistant runs, returned as passages
// instead of an answer — one embedding call, no generation. Any member may search.
// Phase 21 — narrated videos rendered from the course's own materials.
//
// Every call here is scoped to the member who made the job, not to the course: a video can be
// grounded on the requester's own private notes, so it inherits their visibility. That is why
// there is no "the course's videos" endpoint to call.
export const videosApi = {
  // Whether this installation can make videos at all, plus this member's jobs. One request, so the
  // button and the list cannot disagree about whether the feature exists.
  library: (courseId: string) =>
    request<VideoLibrary>(`/courses/${courseId}/videos`, { auth: true }),
  // 202 and a job handle. The render takes minutes; there is no synchronous version of this.
  request: (courseId: string, topic: string) =>
    request<VideoJob>(`/courses/${courseId}/videos`, {
      method: 'POST',
      body: { topic },
      auth: true,
    }),
  get: (courseId: string, jobId: string) =>
    request<VideoJob>(`/courses/${courseId}/videos/${jobId}`, { auth: true }),
  remove: (courseId: string, jobId: string) =>
    request<void>(`/courses/${courseId}/videos/${jobId}`, { method: 'DELETE', auth: true }),
  // The mp4 and its caption track, both behind the bearer token — a <video src> pointing at the
  // API would be an unauthenticated request, and these bytes can come from private notes. The
  // caller turns them into object URLs and revokes them when the player unmounts.
  file: (courseId: string, jobId: string) =>
    fetchBlob(`/courses/${courseId}/videos/${jobId}/file`),
  captions: (courseId: string, jobId: string) =>
    fetchBlob(`/courses/${courseId}/videos/${jobId}/captions`),
}

export const searchApi = {
  query: (courseId: string, q: string, limit?: number) =>
    request<SearchResponse>(
      `/courses/${courseId}/search?q=${encodeURIComponent(q)}${limit ? `&limit=${limit}` : ''}`,
      { auth: true },
    ),
}

// Instructors and owners only — a plain MEMBER gets a 403, so the rail hides the link rather
// than offering a page that always fails. `days` bounds the counts and the lecture heat; topic
// clusters ignore it by design.
export const analyticsApi = {
  confusion: (courseId: string, days?: number) =>
    request<ConfusionReport>(
      `/courses/${courseId}/analytics/confusion${days ? `?days=${days}` : ''}`,
      { auth: true },
    ),
}

// Admin-only. A non-admin token gets a 403 here, so callers should gate the UI on the user's
// role rather than letting the page load and fail.
export const adminApi = {
  costs: (days?: number) =>
    request<CostSummary>(`/admin/costs${days ? `?days=${days}` : ''}`, { auth: true }),
}

export const invitesApi = {
  preview: (token: string) =>
    request<InvitePreviewResponse>(`/invites/${token}`, { auth: true }),
  accept: (token: string) =>
    request<CourseResponse>(`/invites/${token}/accept`, { method: 'POST', auth: true }),
}

// --- Chat streaming -------------------------------------------------------------------------

// Callbacks the caller supplies to receive the stream as it unfolds. onMeta fires once up front
// (conversation id + citations), onDelta once per token, onDone at a clean finish, onError on
// any failure (network, auth, or a server-side `error` event).
export interface ChatStreamHandlers {
  onMeta: (event: ChatMetaEvent) => void
  onDelta: (text: string) => void
  onDone: (event: ChatDoneEvent) => void
  onError: (message: string) => void
}

// Parse a Server-Sent Events stream and dispatch each event to the handlers. Events are
// separated by a blank line; within one, `event:` names it and `data:` carries the JSON.
async function consumeSse(response: Response, handlers: ChatStreamHandlers): Promise<void> {
  const reader = response.body!.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const dispatch = (block: string) => {
    let name = 'message'
    const dataLines: string[] = []
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) name = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
    }
    if (dataLines.length === 0) return
    const data = JSON.parse(dataLines.join('\n'))
    if (name === 'meta') handlers.onMeta(data as ChatMetaEvent)
    else if (name === 'delta') handlers.onDelta((data as { text: string }).text)
    else if (name === 'done') handlers.onDone(data as ChatDoneEvent)
    else if (name === 'error') handlers.onError((data as { message: string }).message)
  }

  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n')
    let boundary
    while ((boundary = buffer.indexOf('\n\n')) !== -1) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      if (block.trim()) dispatch(block)
    }
  }
}

export const chatApi = {
  // Opens the SSE chat stream. Mirrors `request`'s single-retry-on-401 refresh, then hands the
  // live response to consumeSse. `signal` lets the caller abort an in-flight stream.
  async stream(
    courseId: string,
    body: { question: string; conversationId: string | null },
    handlers: ChatStreamHandlers,
    signal?: AbortSignal,
  ): Promise<void> {
    const open = (token: string | null) =>
      fetch(`${API_BASE}/courses/${courseId}/chat/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify(body),
        signal,
      })

    let res = await open(tokenStore.getAccess())
    if (res.status === 401 && (await tryRefresh())) {
      res = await open(tokenStore.getAccess())
    }
    if (!res.ok || !res.body) throw await toError(res)
    await consumeSse(res, handlers)
  },

  // The escape hatch on a refusal (Phase 20.2): the same question answered from general knowledge,
  // labelled as not coming from this course. Not streamed — it is one short answer behind an
  // explicit second click — and it returns no citations, because it has none.
  general: (
    courseId: string,
    body: { question: string; conversationId: string; questionEventId: string | null },
  ) =>
    request<GeneralAnswerResponse>(`/courses/${courseId}/chat/general`, {
      method: 'POST',
      body,
      auth: true,
    }),
}
