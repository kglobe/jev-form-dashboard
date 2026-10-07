alter table datasource add column shared_all boolean not null default false;

create table datasource_share (
    datasource_id uuid not null references datasource (id) on delete cascade,
    username      text not null references account (username),
    primary key (datasource_id, username)
);

create index datasource_share_username on datasource_share (username);

alter table dashboard add column anonymous_link boolean not null default false;
alter table dashboard add constraint dashboard_anonymous_needs_public check (not anonymous_link or public_link);
