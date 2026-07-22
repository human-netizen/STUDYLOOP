-- Quiz attempts (Phase 7.2). One row per submission of a quiz by a user, with the graded score;
-- quiz_attempt_answers keeps the per-question verdict so an attempt can be reviewed afterwards.
create table quiz_attempts (
    id         uuid        primary key,
    quiz_id    uuid        not null references quizzes (id) on delete cascade,
    user_id    uuid        not null references users (id),
    score      int         not null,
    total      int         not null,
    created_at timestamptz not null default now()
);

create index idx_quiz_attempts_quiz_user on quiz_attempts (quiz_id, user_id, created_at desc);

create table quiz_attempt_answers (
    id                    uuid    primary key,
    attempt_id            uuid    not null references quiz_attempts (id) on delete cascade,
    question_id           uuid    not null references quiz_questions (id) on delete cascade,
    selected_option_index int,            -- multiple-choice: the option the user picked
    answer_text           text,           -- short-answer: the user's typed answer
    correct               boolean not null,
    constraint uq_attempt_answer_question unique (attempt_id, question_id)
);

create index idx_quiz_attempt_answers_attempt on quiz_attempt_answers (attempt_id);
