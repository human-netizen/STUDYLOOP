import type {
  CourseResponse,
  CreateCourseRequest,
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

export const invitesApi = {
  preview: (token: string) =>
    request<InvitePreviewResponse>(`/invites/${token}`, { auth: true }),
  accept: (token: string) =>
    request<CourseResponse>(`/invites/${token}/accept`, { method: 'POST', auth: true }),
}
