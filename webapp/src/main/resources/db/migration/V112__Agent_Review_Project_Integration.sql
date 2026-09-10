alter table review_project add column agent_review_run_id bigint(20) default null;
create index IDX__REVIEW_PROJECT__AGENT_RUN on review_project(agent_review_run_id);

-- Historical scalar links survive deletion of a Review Project or its request.
alter table translation_incident
    add column review_type varchar(64) default null,
    add column review_run_id bigint(20) default null,
    add column review_finding_id varchar(36) default null,
    add column resolution_review_project_id bigint(20) default null;
create index IDX__TRANSLATION_INCIDENT__REVIEW_TYPE_STATUS
    on translation_incident(review_type, status, created_date);
create index IDX__TRANSLATION_INCIDENT__REVIEW_RUN
    on translation_incident(review_run_id);
create unique index UK__TRANSLATION_INCIDENT__REVIEW_FINDING
    on translation_incident(review_finding_id);
