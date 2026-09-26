import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { AuthProvider } from './lib/auth'
import { BackendGate } from './components/BackendGate'
import { JobWatchProvider } from './lib/jobs'
import './index.css'
import App from './App.tsx'

// JobWatchProvider sits above the routes and below the router (Phase 29.4): it follows a long job
// across navigations, which is the whole point of it, and it clears its badge by watching the
// location — so it needs the router and the routes need it.
//
// BackendGate sits above AuthProvider so the session check on load does not run until the API is
// up. On Render's free tier the API may still be starting when the page opens.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <BackendGate>
        <AuthProvider>
          <JobWatchProvider>
            <App />
          </JobWatchProvider>
        </AuthProvider>
      </BackendGate>
    </BrowserRouter>
  </StrictMode>,
)
