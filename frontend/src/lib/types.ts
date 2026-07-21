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
