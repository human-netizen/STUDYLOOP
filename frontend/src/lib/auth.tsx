import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { ApiError, authApi, tokenStore } from './api'
import type { LoginRequest, RegisterRequest, UserResponse } from './types'

interface AuthContextValue {
  user: UserResponse | null
  // True only during the initial "do we already have a valid session?" check on mount.
  initializing: boolean
  login: (credentials: LoginRequest) => Promise<void>
  register: (details: RegisterRequest) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserResponse | null>(null)
  const [initializing, setInitializing] = useState(true)

  // On first load, if a token is sitting in storage, confirm it still resolves to a user
  // (this also silently refreshes an expired access token via the api layer).
  useEffect(() => {
    if (!tokenStore.getAccess()) {
      setInitializing(false)
      return
    }
    authApi
      .me()
      .then(setUser)
      // Only a refused token ends the session. A network failure or a 5xx says the server could
      // not answer, not that the token is bad, and clearing on it logs out everyone whose first
      // request of the day reached a server that was still starting.
      .catch((err) => {
        if (err instanceof ApiError && err.status === 401) tokenStore.clear()
      })
      .finally(() => setInitializing(false))
  }, [])

  async function login(credentials: LoginRequest) {
    const result = await authApi.login(credentials)
    tokenStore.set(result.accessToken, result.refreshToken)
    setUser(result.user)
  }

  // Register, then log straight in so the new user lands signed in rather than on the login page.
  async function register(details: RegisterRequest) {
    await authApi.register(details)
    await login({ email: details.email, password: details.password })
  }

  function logout() {
    tokenStore.clear()
    setUser(null)
  }

  return (
    <AuthContext.Provider value={{ user, initializing, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within an AuthProvider')
  return context
}
