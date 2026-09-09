import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { AuthProvider } from './lib/auth'
import { JobWatchProvider } from './lib/jobs'
import './index.css'
import App from './App.tsx'

// JobWatchProvider sits above the routes and below the router (Phase 29.4): it follows a long job
// across navigations, which is the whole point of it, and it clears its badge by watching the
// location — so it needs the router and the routes need it.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <JobWatchProvider>
          <App />
        </JobWatchProvider>
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
)
