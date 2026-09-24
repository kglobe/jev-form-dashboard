create table datasource (
    id               uuid primary key,
    name             text        not null,
    kind             text        not null check (kind in ('upload', 'api')),
    schema_json      jsonb       not null,
    api_url          text,
    api_headers_json jsonb       not null default '{}',
    created_by       text        not null,
    created_at       timestamptz not null default now()
);

create table datasource_rows (
    datasource_id uuid  not null references datasource (id) on delete cascade,
    row_no        int   not null,
    row_json      jsonb not null,
    primary key (datasource_id, row_no)
);

create table dashboard (
    id            uuid primary key,
    name          text        not null,
    datasource_id uuid        not null references datasource (id),
    created_by    text        not null,
    config_json   jsonb       not null,
    updated_at    timestamptz not null default now()
);

create index dashboard_created_by on dashboard (created_by);
create index datasource_created_by on datasource (created_by);
