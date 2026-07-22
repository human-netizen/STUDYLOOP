-- Quizzes (Phase 7.1). A quiz is generated from a course's documents and shared with the course.
-- Questions are either multiple-choice (choices in quiz_question_options, key = correct_option_index)
-- or short-answer (reference answer in expected_answer). explanation is revealed after grading.
create table quizzes (
    id              uuid         primary key,
    course_space_id uuid         not null references course_spaces (id) on delete cascade,
    created_by      uuid         not null references users (id),
    title           varchar(200) not null,
    created_at      timestamptz  not null default now()
);

create index idx_quizzes_course on quizzes (course_space_id);

create table quiz_questions (
    id                   uuid        primary key,
    quiz_id              uuid        not null references quizzes (id) on delete cascade,
    question_index       int         not null,
    type                 varchar(20) not null,  -- MULTIPLE_CHOICE | SHORT_ANSWER
    prompt               text        not null,
    correct_option_index int,                   -- multiple-choice only
    expected_answer      text,                  -- short-answer only
    explanation          text,
    constraint uq_quiz_question_index unique (quiz_id, question_index)
);

create index idx_quiz_questions_quiz on quiz_questions (quiz_id, question_index);

create table quiz_question_options (
    id           uuid primary key,
    question_id  uuid not null references quiz_questions (id) on delete cascade,
    option_index int  not null,
    text         text not null,
    constraint uq_quiz_option_index unique (question_id, option_index)
);

create index idx_quiz_options_question on quiz_question_options (question_id, option_index);
