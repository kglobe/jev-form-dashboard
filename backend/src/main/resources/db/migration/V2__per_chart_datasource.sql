-- 多來源 spec 第 5 節：來源從 dashboard.datasource_id 搬到 config_json 的每張圖上，
-- 舊的 intents 變成只有一組的 basis。卡片順序不變；layout 是空陣列時 jsonb_agg 回 NULL，用 coalesce 補回 []。
update dashboard d
set config_json = jsonb_set(d.config_json - 'intents', '{layout}',
        coalesce((select jsonb_agg(e || jsonb_build_object('datasourceId', d.datasource_id) order by n)
                  from jsonb_array_elements(d.config_json -> 'layout') with ordinality as t(e, n)),
                 '[]'::jsonb))
    || case when jsonb_typeof(d.config_json -> 'intents') = 'array' and d.config_json -> 'intents' <> '[]'::jsonb
            then jsonb_build_object('basis', jsonb_build_array(
                     jsonb_build_object('datasourceId', d.datasource_id, 'intents', d.config_json -> 'intents')))
            else '{}'::jsonb
       end;

-- 外鍵跟著欄位一起刪；目前沒有刪除資料來源的 API，不會出現指到不存在來源的圖
alter table dashboard drop column datasource_id;
