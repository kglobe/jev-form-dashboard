-- B3 spec 4.1：每個來源的比率清單，順序就是使用者加入的順序；舊的來源是空清單，不用搬資料
alter table datasource add column ratios_json jsonb not null default '[]';
