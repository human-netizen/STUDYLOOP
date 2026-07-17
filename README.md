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

## Project status

In active development. Current milestone: **Phase 0 — setup & skeleton** ✅ backend boots against Supabase Postgres with a green health check.

Roadmap: auth (JWT) → course spaces → document ingestion pipeline → RAG chat with citations → streaming + deploy (MVP) → quizzes & SM-2 → analytics & knowledge loop.
