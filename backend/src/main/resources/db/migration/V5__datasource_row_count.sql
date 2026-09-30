-- 建立來源時記下筆數，分析時直接用，外部 API 不用為了算筆數整批抓回來。
-- 上傳的資料列建立後不會變，這裡直接補；舊的外部 API、合併來源補不了，留 null，分析時照舊載入來算
alter table datasource add column row_count integer;
update datasource d set row_count = (select count(*) from datasource_rows r where r.datasource_id = d.id)
where d.kind = 'upload';
