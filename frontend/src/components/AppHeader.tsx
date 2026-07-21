import { Link } from 'react-router-dom'
import { useAuth } from '../lib/auth'

// The top bar shown on every signed-in page: brand on the left, current user + sign-out on
// the right.
export function AppHeader() {
  const { user, logout } = useAuth()

  return (
    <header className="flex items-center justify-between border-b border-slate-200 bg-white px-6 py-4 dark:border-slate-800 dark:bg-slate-900">
      <Link to="/" className="text-lg font-semibold tracking-tight text-slate-900 dark:text-slate-100">
        StudyLoop
      </Link>
      <div className="flex items-center gap-4">
        <span className="text-sm text-slate-500 dark:text-slate-400">{user?.email}</span>
        <button
          type="button"
          onClick={logout}
          className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-700 transition hover:bg-slate-100 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800"
        >
          Sign out
        </button>
      </div>
    </header>
  )
}
