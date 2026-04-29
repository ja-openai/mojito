alter table term_index_occurrence
    drop foreign key FK__TERM_INDEX_OCCURRENCE__ENTRY;

alter table term_index_occurrence
    drop index UK__TERM_INDEX_OCCURRENCE__ENTRY_TU_SPAN_EXTRACTOR;

alter table term_index_occurrence
    drop index I__TERM_INDEX_OCCURRENCE__REPOSITORY_ENTRY;

alter table term_index_refresh_run_entry
    drop foreign key FK__TERM_INDEX_REFRESH_RUN_ENTRY__TERM_INDEX_ENTRY;

alter table term_index_refresh_run_entry
    drop index UK__TERM_INDEX_REFRESH_RUN_ENTRY__RUN_ENTRY;

alter table term_index_refresh_run_entry
    drop index I__TERM_INDEX_REFRESH_RUN_ENTRY__TERM_INDEX_ENTRY;

alter table glossary_term_index_link
    drop foreign key FK__GLOSSARY_TERM_INDEX_LINK__TERM_METADATA;

alter table glossary_term_index_link
    drop foreign key FK__GLOSSARY_TERM_INDEX_LINK__TERM_INDEX_ENTRY;

alter table glossary_term_index_link
    drop index UK__GLOSSARY_TERM_INDEX_LINK__TERM_ENTRY_RELATION;

alter table glossary_term_index_link
    drop index I__GLOSSARY_TERM_INDEX_LINK__TERM_INDEX_ENTRY;

alter table term_index_entry
    drop index UK__TERM_INDEX_ENTRY__LOCALE_KEY;

rename table glossary_term_index_link to glossary_term_index_link_legacy;

rename table term_index_entry to term_index_extracted_term;

alter table term_index_extracted_term
    drop column signal_summary;

alter table term_index_extracted_term
    add column review_status varchar(32) not null default 'TO_REVIEW',
    add column review_authority varchar(32) not null default 'NONE',
    add column review_reason varchar(64) DEFAULT NULL,
    add column review_rationale varchar(2048) DEFAULT NULL,
    add column review_confidence integer DEFAULT NULL,
    add column review_changed_at datetime DEFAULT NULL,
    add column review_changed_by_user_id bigint(20) DEFAULT NULL;

alter table term_index_extracted_term
    add constraint UK__TERM_INDEX_EXTRACTED_TERM__LOCALE_KEY
        unique (source_locale_tag, normalized_key);

alter table term_index_extracted_term
    add constraint FK__TERM_INDEX_EXTRACTED_TERM__REVIEW_CHANGED_BY_USER
        foreign key (review_changed_by_user_id) references user (id);

create index I__TERM_INDEX_EXTRACTED_TERM__REVIEW_STATUS
    on term_index_extracted_term(review_status);

create index I__TERM_INDEX_EXTRACTED_TERM__REVIEW_CHANGED_BY_USER
    on term_index_extracted_term(review_changed_by_user_id);

alter table term_index_occurrence
    change column term_index_entry_id term_index_extracted_term_id bigint(20) not null;

alter table term_index_occurrence
    add constraint FK__TERM_INDEX_OCCURRENCE__EXTRACTED_TERM
        foreign key (term_index_extracted_term_id) references term_index_extracted_term (id);

alter table term_index_occurrence
    add constraint UK__TERM_INDEX_OCCURRENCE__EXTRACTED_TERM_TU_SPAN
        unique (term_index_extracted_term_id, tm_text_unit_id, start_index, end_index, extractor_id);

create index I__TERM_INDEX_OCCURRENCE__REPOSITORY_EXTRACTED_TERM
    on term_index_occurrence(repository_id, term_index_extracted_term_id);

alter table term_index_refresh_run
    change column entry_count extracted_term_count bigint(20) not null default 0,
    add column pollable_task_id bigint(20) DEFAULT NULL;

alter table term_index_refresh_run
    add constraint FK__TERM_INDEX_REFRESH_RUN__POLLABLE_TASK
        foreign key (pollable_task_id) references pollable_task (id);

create unique index UK__TERM_INDEX_REFRESH_RUN__POLLABLE_TASK
    on term_index_refresh_run(pollable_task_id);

alter table term_index_refresh_run_entry
    change column term_index_entry_id term_index_extracted_term_id bigint(20) not null;

alter table term_index_refresh_run_entry
    add constraint FK__TERM_INDEX_REFRESH_RUN_ENTRY__TERM_INDEX_EXTRACTED_TERM
        foreign key (term_index_extracted_term_id) references term_index_extracted_term (id);

alter table term_index_refresh_run_entry
    add constraint UK__TERM_INDEX_REFRESH_RUN_ENTRY__RUN_ENTRY
        unique (refresh_run_id, term_index_extracted_term_id);

create index I__TERM_INDEX_REFRESH_RUN_ENTRY__TERM_INDEX_EXTRACTED_TERM
    on term_index_refresh_run_entry(term_index_extracted_term_id);

create table term_index_candidate (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    term_index_extracted_term_id bigint(20) DEFAULT NULL,
    source_locale_tag varchar(64) not null,
    normalized_key varchar(255) not null,
    term varchar(512) not null,
    label varchar(512) DEFAULT NULL,
    definition varchar(2048) DEFAULT NULL,
    rationale varchar(2048) DEFAULT NULL,
    confidence integer DEFAULT NULL,
    source_type varchar(64) not null,
    source_name varchar(255) not null default '',
    source_external_id varchar(255) DEFAULT NULL,
    candidate_hash varchar(64) not null,
    term_type varchar(32) DEFAULT NULL,
    part_of_speech varchar(64) DEFAULT NULL,
    enforcement varchar(32) DEFAULT NULL,
    do_not_translate bit DEFAULT NULL,
    metadata_json mediumtext DEFAULT NULL,
    review_status varchar(32) not null default 'TO_REVIEW',
    review_authority varchar(32) not null default 'NONE',
    review_reason varchar(64) DEFAULT NULL,
    review_rationale varchar(2048) DEFAULT NULL,
    review_confidence integer DEFAULT NULL,
    review_changed_at datetime DEFAULT NULL,
    review_changed_by_user_id bigint(20) DEFAULT NULL,
    primary key (id)
);

insert into term_index_candidate (
    created_date,
    last_modified_date,
    term_index_extracted_term_id,
    source_locale_tag,
    normalized_key,
    term,
    label,
    source_type,
    source_name,
    candidate_hash,
    review_status,
    review_authority
)
select
    extracted_term.created_date,
    extracted_term.last_modified_date,
    extracted_term.id,
    extracted_term.source_locale_tag,
    extracted_term.normalized_key,
    extracted_term.display_term,
    extracted_term.display_term,
    'LEGACY',
    'term-index-migration',
    sha2(concat('legacy:', extracted_term.id), 256),
    'ACCEPTED',
    'NONE'
from term_index_extracted_term extracted_term
where exists (
    select 1
    from glossary_term_index_link_legacy link
    where link.term_index_entry_id = extracted_term.id
);

alter table term_index_candidate
    add constraint FK__TERM_INDEX_CANDIDATE__EXTRACTED_TERM
        foreign key (term_index_extracted_term_id) references term_index_extracted_term (id);

alter table term_index_candidate
    add constraint UK__TERM_INDEX_CANDIDATE__SOURCE_HASH
        unique (source_type, source_name, candidate_hash);

alter table term_index_candidate
    add constraint FK__TERM_INDEX_CANDIDATE__REVIEW_CHANGED_BY_USER
        foreign key (review_changed_by_user_id) references user (id);

create index I__TERM_INDEX_CANDIDATE__EXTRACTED_TERM
    on term_index_candidate(term_index_extracted_term_id);

create index I__TERM_INDEX_CANDIDATE__KEY
    on term_index_candidate(source_locale_tag, normalized_key);

create index I__TERM_INDEX_CANDIDATE__SOURCE_EXTERNAL_ID
    on term_index_candidate(source_type, source_name, source_external_id);

create index I__TERM_INDEX_CANDIDATE__REVIEW_STATUS
    on term_index_candidate(review_status);

create index I__TERM_INDEX_CANDIDATE__REVIEW_CHANGED_BY_USER
    on term_index_candidate(review_changed_by_user_id);

create table glossary_term_index_decision (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    glossary_id bigint(20) not null,
    term_index_candidate_id bigint(20) not null,
    decision varchar(32) not null,
    reason varchar(2048) DEFAULT NULL,
    primary key (id)
);

alter table glossary_term_index_decision
    add constraint FK__GLOSSARY_TERM_INDEX_DECISION__GLOSSARY
        foreign key (glossary_id) references glossary (id);

alter table glossary_term_index_decision
    add constraint FK__GLOSSARY_TERM_INDEX_DECISION__CANDIDATE
        foreign key (term_index_candidate_id) references term_index_candidate (id);

alter table glossary_term_index_decision
    add constraint UK__GLOSSARY_TERM_INDEX_DECISION__GLOSSARY_CANDIDATE
        unique (glossary_id, term_index_candidate_id);

create index I__GLOSSARY_TERM_INDEX_DECISION__CANDIDATE
    on glossary_term_index_decision(term_index_candidate_id);

create table glossary_term_index_link (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    glossary_term_metadata_id bigint(20) not null,
    term_index_candidate_id bigint(20) not null,
    relation_type varchar(32) not null,
    confidence integer DEFAULT NULL,
    rationale varchar(2048) DEFAULT NULL,
    primary key (id)
);

insert into glossary_term_index_link (
    created_date,
    last_modified_date,
    glossary_term_metadata_id,
    term_index_candidate_id,
    relation_type,
    confidence,
    rationale
)
select
    link.created_date,
    link.last_modified_date,
    link.glossary_term_metadata_id,
    candidate.id,
    link.relation_type,
    link.confidence,
    link.rationale
from glossary_term_index_link_legacy link
join term_index_candidate candidate
    on candidate.term_index_extracted_term_id = link.term_index_entry_id;

alter table glossary_term_index_link
    add constraint FK__GLOSSARY_TERM_INDEX_LINK__TERM_METADATA
        foreign key (glossary_term_metadata_id) references glossary_term_metadata (id);

alter table glossary_term_index_link
    add constraint FK__GLOSSARY_TERM_INDEX_LINK__CANDIDATE
        foreign key (term_index_candidate_id) references term_index_candidate (id);

alter table glossary_term_index_link
    add constraint UK__GLOSSARY_TERM_INDEX_LINK__TERM_CANDIDATE_RELATION
        unique (glossary_term_metadata_id, term_index_candidate_id, relation_type);

create index I__GLOSSARY_TERM_INDEX_LINK__CANDIDATE
    on glossary_term_index_link(term_index_candidate_id);

drop table glossary_term_index_link_legacy;
