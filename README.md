# 📚 StudyLoop — AI Course Companion

> Upload your course materials once; StudyLoop becomes a course-scoped tutor that answers **with page-level citations**, generates quizzes from *your* lectures, and schedules your revision — while showing the instructor exactly where the class is confused.



---

## Why not just ChatGPT?

| Generic chatbot | StudyLoop |
|---|---|
| Answers from the open internet | Answers **only from the course corpus**, with citations like `[Lecture 7, p. 12]` |
| Hallucinates confidently | Says **"not in your materials"** when retrieval confidence is low |
| Can't test you on *your* slides | Quizzes & flashcards generated from selected lectures |
| No revision planning | SM-2 spaced-repetition queue built from *your* mistakes |
| Tells the teacher nothing | Anonymized confusion heatmap per lecture/topic |

## Core features

1. **Grounded Q&A (RAG)** — hybrid retrieval (vector + full-text) over ingested PDFs/slides, streamed answers with clickable citations that open the PDF at the cited page
2. **Assessment engine** — one-click MCQ + short-answer quizzes with auto-grading and explanations; save any Q&A exchange as a flashcard
3. **Spaced repetition** — missed questions and saved cards enter an SM-2 review queue; StudyLoop tells you what to revise *today*
4. **Confusion analytics** — instructors see which topics the class asks about most, clustered by embedding similarity
5. **Knowledge loop** — questions the AI can't ground escalate to a course forum; accepted answers are ingested back into the corpus

## Tech stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 4.1 (Web MVC, Security + JWT, Data JPA, Validation, Actuator) |
| Database | PostgreSQL on Supabase with **pgvector** · Flyway migrations |
| AI | Spring AI — chat, embeddings, structured output |
| Frontend | React + Vite + TypeScript + Tailwind CSS |
| CI/CD | GitHub Actions · Docker image built in CI · cloud deploy |

## Running locally

**Prerequisites:** JDK 21+, a free [Supabase](https://supabase.com) project (with the `vector` extension enabled).

1. Create `backend/.env.properties` (git-ignored) with your Supabase **session pooler** credentials:

   ```properties
   DB_URL=jdbc:postgresql://<your-pooler-host>:5432/postgres
   DB_USERNAME=postgres.<your-project-ref>
   DB_PASSWORD=<your-db-password>
   ```

2. Run the backend:

   ```bash
   cd backend
   ./mvnw spring-boot:run
   ```

3. Verify: `http://localhost:8080/actuator/health` → `"status": "UP"` with `db: UP`.

   For grounded chat, also set `COHERE_API_KEY=<your-key>` in `backend/.env.properties` (a free
   key from [Cohere](https://dashboard.cohere.com/api-keys) powers both embeddings and chat).

4. Run the frontend against it:

   ```bash
   cd frontend
   npm install
   npm run dev            # http://localhost:5173
   ```

## Deployment

Both apps ship as Docker images (`backend/Dockerfile`, `frontend/Dockerfile`); CI builds both on
every push to verify they're deployable. A typical cloud setup (Railway/Render + Supabase):

- **Database** — a Supabase Postgres project with the `vector` extension; Flyway applies the
  schema automatically on first boot.
- **Backend** — deploy `backend/` as a Docker service. Attach a **persistent disk mounted at
  `/data`** so uploaded PDFs survive restarts, and set these environment variables:

  | Variable | Purpose |
  |---|---|
  | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Supabase session-pooler credentials |
  | `JWT_SECRET` | HMAC signing key, ≥ 32 bytes |
  | `COHERE_API_KEY` | embeddings + chat |
  | `CORS_ALLOWED_ORIGINS` | the deployed frontend origin (comma-separated) |
  | `DOCUMENTS_DIR` | defaults to `/data/documents` in the image |

- **Frontend** — build `frontend/` with `--build-arg VITE_API_URL=https://<your-backend-host>`
  (Vite inlines it at build time), then serve the resulting nginx image. Point
  `CORS_ALLOWED_ORIGINS` on the backend at this host.

The backend exposes `/actuator/health` for the platform's health check.

## Project status

MVP complete through **Phase 6 — streaming answers + citation viewer + deploy**: JWT auth, course
spaces with role-based access, an async PDF ingestion pipeline (extract → chunk → embed), hybrid
(vector + full-text) retrieval with a confidence gate, and RAG chat that **streams** grounded
answers whose `[n]` citations open the source PDF at the cited page. Containerized and CI-built.

Roadmap next: quizzes & SM-2 spaced repetition → confusion analytics & knowledge loop.
