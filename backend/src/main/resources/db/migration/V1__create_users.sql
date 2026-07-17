create table users (
    id            uuid         primary key,
    email         varchar(255) not null unique,
    password_hash varchar(100) not null,
    display_name  varchar(100) not null,
    role          varchar(20)  not null,
    created_at    timestamptz  not null default now()
);
