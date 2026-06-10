alter table branch add column cleanup_date datetime DEFAULT NULL;

create index I__BRANCH__CLEANUP_DATE on branch(cleanup_date);
