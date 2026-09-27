-- spec 4.1：合併來源（子專案 B1）。datasource_kind_check 是 V1 行內 check 的預設名稱
alter table datasource add column merge_json jsonb;
alter table datasource drop constraint datasource_kind_check;
alter table datasource add constraint datasource_kind_check check (kind in ('upload', 'api', 'merged'));
alter table datasource add constraint datasource_merge_json_check check ((kind = 'merged') = (merge_json is not null));
