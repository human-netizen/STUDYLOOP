#  StudyLoop — AI Course Companion

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

## What's built

Seventeen database migrations, 158 tests running against real Postgres + pgvector.

| | |
|---|---|
| **Accounts** | Register, log in, stay signed in. BCrypt hashing, JWT access + refresh tokens, role-based access on every endpoint. |
| **Course spaces** | Owners, instructors and members. Invite by share link or by email address; links can be revoked and expire. |
| **Uploading material** | Drop in a PDF and watch it go through extraction, chunking and embedding without blocking the page. Duplicate files are rejected by content hash, not by filename. |
| **Grounded answers** | Vector search and full-text search run in parallel and are fused into one ranked set. Answers stream in token by token, carry `[n]` citations, and remember the conversation. |
| **Refusal** | When nothing in the course matches well enough, StudyLoop says **"not in your materials"** instead of inventing an answer. |
| **Citation viewer** | Clicking a citation opens that document at that page. |
| **Quizzes** | Multiple-choice and short-answer quizzes generated from chosen lectures, graded automatically with explanations for every answer. |
| **Flashcards** | Generated from a document, or saved from any answer you want to keep. |
| **Revision queue** | An SM-2 spaced-repetition schedule. Questions you got wrong enrol themselves; the app tells you what is due today. |
| **Document summaries** | A summary and a key-term glossary per document, generated once and cached. |
| **Search** | A search box over the course that returns passages grouped by document with the matched words highlighted, and no confidence gate — weak matches are still shown, because you can judge them yourself. |
| **Confusion heatmap** | Instructors see which lectures the class is asking about, questions clustered into topics by meaning, and the questions the materials could not answer at all. |
| **Course forum** | Any refusal can be escalated to the class. Anyone may answer; an instructor accepts one, and the accepted answer is embedded back into the course so the next student gets it from the assistant. |
| **Cost visibility** | Every paid API call is recorded and priced from the provider's own billing figures. An admin dashboard shows spend by feature and by day. |
| **Answer cache** | Near-identical questions reuse a previous answer instead of paying for it twice. Uploading a document clears the course's cache. |
| **Usage limits** | Per-user request limits and a rolling token allowance on everything that costs money. |

## What isn't there yet

- **No public instance.** It runs locally. Docker images for both halves are built on every push, but nothing is hosted.
- **PDFs only.** Slide decks, Word documents, scanned pages and photographed handwriting are not accepted yet.
- **English in practice.** Bangla PDFs frequently extract as garbage, so answers over them are unreliable.
- **Diagrams are invisible to search.** A figure or a table is only found through whatever text sits around it.
- **Starts empty.** No sample course ships with the repo; you upload your own material first.

## What's coming

- **Better retrieval, measured rather than claimed** — a cross-encoder reranking stage, chunks cut along a document's real structure instead of a fixed window, and generated questions indexed alongside each section so a student's phrasing finds a textbook's. Each change is scored on a fixed question set, so improvement is a number, not an opinion.
- **Material that isn't a clean PDF** — scanned pages and broken text layers recovered with a vision model, PowerPoint with its speaker notes, Word documents, and photos of handwritten notes.
- **Diagrams that answer questions** — figures and tables indexed as images, so "the diagram with the three layers" finds the page.
- **Typos and vague questions** — misspellings matched anyway, and genuinely unclear questions rewritten before retrieval.
- **Bangla end to end** — detected on upload, searched with the right language rules, answered in Bangla with citations.
- **Study guides** — a cited, exportable revision guide generated for any topic in the course, with diagrams.
- **A corpus that fills its own gaps** — uploading a document goes back and answers the questions it previously couldn't.

## Tech stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 4.1 (Web MVC, Security + JWT, Data JPA, Validation, Actuator) |
| Database | PostgreSQL on Supabase with **pgvector** · Flyway migrations `V1`–`V17` |
| AI | Cohere, called directly over Spring's `RestClient` — `embed-v4.0` embeddings truncated to 768-d, Command R for chat |
| Extraction | Apache PDFBox, per page |
| Frontend | React 19 + Vite + TypeScript + Tailwind 4 · `react-pdf` for the citation viewer |
| Testing | JUnit 5 + MockMvc against a **second Supabase project** used only by the suite |
| CI/CD | GitHub Actions — build + test on every push, both Docker images built in CI |

Architecture diagrams live in [docs/architecture/](docs/architecture/) — a system overview and the
retrieval pipeline, as `.drawio` files openable at [diagrams.net](https://app.diagrams.net).

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
