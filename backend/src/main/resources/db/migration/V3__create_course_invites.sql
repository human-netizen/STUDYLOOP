create table course_invites (
    id              uuid         primary key,
    course_space_id uuid         not null references course_spaces (id) on delete cascade,
    token           varchar(64)  not null unique,
    email           varchar(255),
    role            varchar(20)  not null,
    created_by      uuid         not null references users (id),
    expires_at      timestamptz,
    revoked         boolean      not null default false,
    created_at      timestamptz  not null default now()
);

create index idx_course_invites_course on course_invites (course_space_id);
