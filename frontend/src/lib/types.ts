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
