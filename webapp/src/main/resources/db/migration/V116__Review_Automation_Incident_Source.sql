alter table review_automation add column review_source varchar(32) not null default 'CURRENT_TRANSLATIONS';
alter table review_automation add column incident_review_type varchar(64);
alter table review_automation add column incident_scope varchar(32) not null default 'REVIEW_FEATURES';
