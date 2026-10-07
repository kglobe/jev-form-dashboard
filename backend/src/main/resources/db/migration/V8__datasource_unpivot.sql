-- 寬表轉長表 spec 2.6：上傳時的轉換方式，更新資料時照同樣方式轉；沒轉過的來源是 null
alter table datasource add column unpivot_json jsonb;
