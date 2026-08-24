import { Navigate, Route, Routes } from 'react-router-dom'
import { LoginPage } from './pages/LoginPage'
import { RegisterPage } from './pages/RegisterPage'
import { CoursesPage } from './pages/CoursesPage'
import { CourseDetailPage } from './pages/CourseDetailPage'
import { ChatPage } from './pages/ChatPage'
import { CourseSearchPage } from './pages/CourseSearchPage'
import { CourseForumPage } from './pages/CourseForumPage'
import { ForumThreadPage } from './pages/ForumThreadPage'
import { QuizzesPage } from './pages/QuizzesPage'
import { QuizPage } from './pages/QuizPage'
import { FlashcardsPage } from './pages/FlashcardsPage'
import { ReviewPage } from './pages/ReviewPage'
import { CourseNotesPage } from './pages/CourseNotesPage'
import { CourseVideosPage } from './pages/CourseVideosPage'
import { CourseConfusionPage } from './pages/CourseConfusionPage'
import { JoinPage } from './pages/JoinPage'
import { AdminCostsPage } from './pages/AdminCostsPage'
import { ProtectedRoute } from './components/ProtectedRoute'

function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route
        path="/"
        element={
          <ProtectedRoute>
            <CoursesPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/courses/:id"
        element={
          <ProtectedRoute>
            <CourseDetailPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/courses/:id/chat"
        element={
          <ProtectedRoute>
            <ChatPage />
          </ProtectedRoute>
        }
      />
      {/* Where a refused question goes. Open to every member — the whole point is that a
          classmate can answer what the materials couldn't. */}
      <Route
        path="/courses/:id/forum"
        element={
          <ProtectedRoute>
            <CourseForumPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/courses/:id/forum/:threadId"
        element={
          <ProtectedRoute>
            <ForumThreadPage />
          </ProtectedRoute>
        }
      />
      {/* The other way into the same retrieval: passages instead of an answer. Any member. */}
      <Route
        path="/courses/:id/search"
        element={
          <ProtectedRoute>
            <CourseSearchPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/courses/:id/quizzes"
        element={
          <ProtectedRoute>
            <QuizzesPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/courses/:id/quizzes/:quizId"
        element={
          <ProtectedRoute>
            <QuizPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/courses/:id/flashcards"
        element={
          <ProtectedRoute>
            <FlashcardsPage />
          </ProtectedRoute>
        }
      />
      {/* Phase 21: narrated videos built from the course's materials. Routed unconditionally —
          the page itself reports that the renderer is absent, which is the one case where a
          pasted link has to say something rather than 404. */}
      <Route
        path="/courses/:id/videos"
        element={
          <ProtectedRoute>
            <CourseVideosPage />
          </ProtectedRoute>
        }
      />
      {/* Phase 16.3: photograph a page of handwriting and have it read. Any member — what they
          add is private to them until a manager promotes it. */}
      <Route
        path="/courses/:id/notes"
        element={
          <ProtectedRoute>
            <CourseNotesPage />
          </ProtectedRoute>
        }
      />
      {/* Instructor-only, but routed for everyone: the page renders the 403 as a message, so a
          pasted link doesn't dead-end. */}
      <Route
        path="/courses/:id/confusion"
        element={
          <ProtectedRoute>
            <CourseConfusionPage />
          </ProtectedRoute>
        }
      />
      {/* The queue spans every course, so it also lives at the top level. */}
      <Route
        path="/review"
        element={
          <ProtectedRoute>
            <ReviewPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/courses/:id/review"
        element={
          <ProtectedRoute>
            <ReviewPage />
          </ProtectedRoute>
        }
      />
      {/* Spend is an operator's view, not a course's. The page checks the role itself; the
          backend refuses a non-admin token regardless. */}
      <Route
        path="/admin/costs"
        element={
          <ProtectedRoute>
            <AdminCostsPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/join/:token"
        element={
          <ProtectedRoute>
            <JoinPage />
          </ProtectedRoute>
        }
      />
      {/* Unknown paths fall back to the (guarded) home route. */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default App
