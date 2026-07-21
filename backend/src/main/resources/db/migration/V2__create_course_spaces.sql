create table course_spaces (
    id          uuid          primary key,
    name        varchar(100)  not null,
    description varchar(1000),
    owner_id    uuid          not null references users (id),
    created_at  timestamptz   not null default now()
);

create table memberships (
    id              uuid        primary key,
    course_space_id uuid        not null references course_spaces (id) on delete cascade,
    user_id         uuid        not null references users (id) on delete cascade,
    role            varchar(20) not null,
    created_at      timestamptz not null default now(),
    constraint uq_membership_course_user unique (course_space_id, user_id)
);

create index idx_memberships_user on memberships (user_id);
create index idx_memberships_course on memberships (course_space_id);
