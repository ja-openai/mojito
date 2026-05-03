delete link
from glossary_term_index_link link
join term_index_candidate candidate
    on candidate.id = link.term_index_candidate_id
left join term_index_extracted_term extracted_term
    on extracted_term.id = candidate.term_index_extracted_term_id
where candidate.source_locale_tag = 'root'
   or extracted_term.source_locale_tag = 'root';

delete decision_row
from glossary_term_index_decision decision_row
join term_index_candidate candidate
    on candidate.id = decision_row.term_index_candidate_id
left join term_index_extracted_term extracted_term
    on extracted_term.id = candidate.term_index_extracted_term_id
where candidate.source_locale_tag = 'root'
   or extracted_term.source_locale_tag = 'root';

delete candidate
from term_index_candidate candidate
left join term_index_extracted_term extracted_term
    on extracted_term.id = candidate.term_index_extracted_term_id
where candidate.source_locale_tag = 'root'
   or extracted_term.source_locale_tag = 'root';

delete occurrence
from term_index_occurrence occurrence
join term_index_extracted_term extracted_term
    on extracted_term.id = occurrence.term_index_extracted_term_id
where extracted_term.source_locale_tag = 'root';

delete run_entry
from term_index_refresh_run_entry run_entry
join term_index_extracted_term extracted_term
    on extracted_term.id = run_entry.term_index_extracted_term_id
where extracted_term.source_locale_tag = 'root';

delete from term_index_extracted_term
where source_locale_tag = 'root';

create table review_project_text_unit_feedback (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    review_project_text_unit_id bigint(20) NOT NULL,
    reviewer_user_id bigint(20) NOT NULL,
    recommendation varchar(32) NOT NULL,
    confidence integer DEFAULT NULL,
    notes varchar(4000) DEFAULT NULL,
    version bigint(20) DEFAULT 0,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    primary key (id)
);

alter table review_project_text_unit_feedback
    add constraint FK__REVIEW_PROJECT_TEXT_UNIT_FEEDBACK__REVIEW_PROJECT_TEXT_UNIT
        foreign key (review_project_text_unit_id) references review_project_text_unit (id);

alter table review_project_text_unit_feedback
    add constraint FK__REVIEW_PROJECT_TEXT_UNIT_FEEDBACK__REVIEWER
        foreign key (reviewer_user_id) references user (id);

alter table review_project_text_unit_feedback
    add constraint UK__REVIEW_PROJECT_TEXT_UNIT_FEEDBACK__TEXT_UNIT_REVIEWER
        unique (review_project_text_unit_id, reviewer_user_id);

create index I__REVIEW_PROJECT_TEXT_UNIT_FEEDBACK__TEXT_UNIT
    on review_project_text_unit_feedback(review_project_text_unit_id);

create index I__REVIEW_PROJECT_TEXT_UNIT_FEEDBACK__REVIEWER
    on review_project_text_unit_feedback(reviewer_user_id);

alter table review_project
    add column terminology_phase varchar(32) DEFAULT NULL;

alter table review_project_text_unit
    add column term_index_candidate_id bigint(20) DEFAULT NULL,
    add column target_glossary_id bigint(20) DEFAULT NULL;

alter table review_project_text_unit
    add constraint FK__REVIEW_PROJECT_TEXT_UNIT__TERM_INDEX_CANDIDATE
        foreign key (term_index_candidate_id) references term_index_candidate (id);

alter table review_project_text_unit
    add constraint FK__REVIEW_PROJECT_TEXT_UNIT__TARGET_GLOSSARY
        foreign key (target_glossary_id) references glossary (id);

create index I__REVIEW_PROJECT_TEXT_UNIT__TERM_INDEX_CANDIDATE
    on review_project_text_unit(term_index_candidate_id);

create index I__REVIEW_PROJECT_TEXT_UNIT__TARGET_GLOSSARY
    on review_project_text_unit(target_glossary_id);

create table term_index_automation_run (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    type varchar(64) not null,
    status varchar(32) not null,
    requested_repository_ids varchar(2048) default null,
    pollable_task_id bigint(20) default null,
    message longtext default null,
    entry_count bigint(20) not null default 0,
    processed_entry_count bigint(20) not null default 0,
    reviewable_entry_count bigint(20) not null default 0,
    reviewed_entry_count bigint(20) not null default 0,
    updated_entry_count bigint(20) not null default 0,
    candidate_count bigint(20) not null default 0,
    created_candidate_count bigint(20) not null default 0,
    updated_candidate_count bigint(20) not null default 0,
    skipped_existing_candidate_count bigint(20) not null default 0,
    accepted_count bigint(20) not null default 0,
    to_review_count bigint(20) not null default 0,
    rejected_count bigint(20) not null default 0,
    skipped_human_reviewed_count bigint(20) not null default 0,
    batch_count int not null default 0,
    completed_batch_count int not null default 0,
    started_at datetime default null,
    completed_at datetime default null,
    error_message varchar(2048) default null,
    primary key (id)
);

alter table term_index_automation_run
    add constraint FK__TERM_INDEX_AUTOMATION_RUN__POLLABLE_TASK
        foreign key (pollable_task_id) references pollable_task (id);

create index I__TERM_INDEX_AUTOMATION_RUN__TYPE_ID
    on term_index_automation_run(type, id);

create unique index UK__TERM_INDEX_AUTOMATION_RUN__POLLABLE_TASK_ID
    on term_index_automation_run(pollable_task_id);
