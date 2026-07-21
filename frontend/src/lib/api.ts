import type {
  ChatDoneEvent,
  ChatMetaEvent,
  CourseResponse,
  CreateCourseRequest,
  DocumentResponse,
  InvitePreviewResponse,
  LoginRequest,
  PageResponse,
  RegisterRequest,
  TokenResponse,
  UserResponse,
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
export class ApiError extends Error {
  readonly status: number
  readonly fieldErrors?: Record<string, string>

  constructor(status: number, message: string, fieldErrors?: Record<string, string>) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
  }
}

async function toError(res: Response): Promise<ApiError> {
  let message = res.statusText
  let fieldErrors: Record<string, string> | undefined
  try {
    const body = await res.json()
    message = body.detail ?? body.title ?? message
    if (body.errors) fieldErrors = body.errors as Record<string, string>
  } catch {
    // non-JSON body — keep the status text
  }
  return new ApiError(res.status, message, fieldErrors)
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
  // Fetches the raw PDF bytes (auth-guarded) as a Blob for client-side rendering. Same
  // single-retry-on-401 refresh as the JSON calls; the caller turns it into an object URL.
  async fileBlob(courseId: string, documentId: string): Promise<Blob> {
    const path = `/courses/${courseId}/documents/${documentId}/file`
    const get = (token: string | null) =>
      fetch(API_BASE + path, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
    let res = await get(tokenStore.getAccess())
    if (res.status === 401 && (await tryRefresh())) {
      res = await get(tokenStore.getAccess())
    }
    if (!res.ok) throw await toError(res)
    return res.blob()
  },
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
}
