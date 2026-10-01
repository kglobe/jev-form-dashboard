create table account (
    username             text primary key,
    password_hash        text,
    admin                boolean     not null default false,
    enabled              boolean     not null default true,
    oidc_issuer          text,
    oidc_subject         text,
    sessions_valid_after timestamptz,
    created_at           timestamptz not null default now(),
    unique (oidc_issuer, oidc_subject)
);

alter table dashboard add column public_link boolean not null default false;

create table dashboard_share (
    dashboard_id uuid not null references dashboard (id) on delete cascade,
    username     text not null references account (username),
    primary key (dashboard_id, username)
);

create index dashboard_share_username on dashboard_share (username);
