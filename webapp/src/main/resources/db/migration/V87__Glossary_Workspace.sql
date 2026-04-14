create table glossary (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    name varchar(255) NOT NULL,
    description varchar(1024) DEFAULT NULL,
    enabled bit not null default true,
    priority integer not null default 0,
    backing_repository_id bigint(20) not null,
    scope_mode varchar(32) not null default 'SELECTED_REPOSITORIES',
    asset_path varchar(255) not null default 'glossary',
    primary key (id)
);

alter table glossary
    add constraint UK__GLOSSARY__NAME unique (name);

alter table glossary
    add constraint FK__GLOSSARY__BACKING_REPOSITORY
        foreign key (backing_repository_id) references repository (id);

create index I__GLOSSARY__BACKING_REPOSITORY__ID
    on glossary(backing_repository_id);

create table glossary_repository (
    glossary_id bigint(20) NOT NULL,
    repository_id bigint(20) NOT NULL
);

alter table glossary_repository
    add constraint FK__GLOSSARY_REPOSITORY__GLOSSARY
        foreign key (glossary_id) references glossary (id);

alter table glossary_repository
    add constraint FK__GLOSSARY_REPOSITORY__REPOSITORY
        foreign key (repository_id) references repository (id);

alter table glossary_repository
    add constraint UK__GLOSSARY_REPOSITORY__GLOSSARY_REPOSITORY
        unique (glossary_id, repository_id);

create index I__GLOSSARY_REPOSITORY__REPOSITORY
    on glossary_repository(repository_id);

create table glossary_excluded_repository (
    glossary_id bigint(20) NOT NULL,
    repository_id bigint(20) NOT NULL
);

alter table glossary_excluded_repository
    add constraint FK__GLOSSARY_EXCLUDED_REPOSITORY__GLOSSARY
        foreign key (glossary_id) references glossary (id);

alter table glossary_excluded_repository
    add constraint FK__GLOSSARY_EXCLUDED_REPOSITORY__REPOSITORY
        foreign key (repository_id) references repository (id);

alter table glossary_excluded_repository
    add constraint UK__GLOSSARY_EXCLUDED_REPOSITORY__GLOSSARY_REPOSITORY
        unique (glossary_id, repository_id);

create index I__GLOSSARY_EXCLUDED_REPOSITORY__REPOSITORY
    on glossary_excluded_repository(repository_id);

create table glossary_term_metadata (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    glossary_id bigint(20) not null,
    tm_text_unit_id bigint(20) not null,
    part_of_speech varchar(64) DEFAULT NULL,
    term_type varchar(32) DEFAULT NULL,
    enforcement varchar(32) DEFAULT NULL,
    status varchar(32) DEFAULT NULL,
    provenance varchar(32) DEFAULT NULL,
    case_sensitive bit not null default false,
    do_not_translate bit not null default false,
    primary key (id)
);

alter table glossary_term_metadata
    add constraint FK__GLOSSARY_TERM_METADATA__GLOSSARY
        foreign key (glossary_id) references glossary (id);

alter table glossary_term_metadata
    add constraint FK__GLOSSARY_TERM_METADATA__TM_TEXT_UNIT
        foreign key (tm_text_unit_id) references tm_text_unit (id);

alter table glossary_term_metadata
    add constraint UK__GLOSSARY_TERM_METADATA__GLOSSARY_TU
        unique (glossary_id, tm_text_unit_id);

create index I__GLOSSARY_TERM_METADATA__TM_TEXT_UNIT__ID
    on glossary_term_metadata(tm_text_unit_id);

create table glossary_term_evidence (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    glossary_term_metadata_id bigint(20) not null,
    evidence_type varchar(32) not null,
    screenshot_id bigint(20) DEFAULT NULL,
    tm_text_unit_id bigint(20) DEFAULT NULL,
    caption varchar(1024) DEFAULT NULL,
    crop_x integer DEFAULT NULL,
    crop_y integer DEFAULT NULL,
    crop_width integer DEFAULT NULL,
    crop_height integer DEFAULT NULL,
    sort_order integer not null default 0,
    image_key varchar(512) DEFAULT NULL,
    primary key (id)
);

alter table glossary_term_evidence
    add constraint FK__GLOSSARY_TERM_EVIDENCE__TERM_METADATA
        foreign key (glossary_term_metadata_id) references glossary_term_metadata (id);

alter table glossary_term_evidence
    add constraint FK__GLOSSARY_TERM_EVIDENCE__SCREENSHOT
        foreign key (screenshot_id) references screenshot (id);

alter table glossary_term_evidence
    add constraint FK__GLOSSARY_TERM_EVIDENCE__TM_TEXT_UNIT
        foreign key (tm_text_unit_id) references tm_text_unit (id);

create index I__GLOSSARY_TERM_EVIDENCE__TERM_METADATA__ID
    on glossary_term_evidence(glossary_term_metadata_id);

create index I__GLOSSARY_TERM_EVIDENCE__SCREENSHOT__ID
    on glossary_term_evidence(screenshot_id);

create index I__GLOSSARY_TERM_EVIDENCE__TM_TEXT_UNIT__ID
    on glossary_term_evidence(tm_text_unit_id);

create index I__GLOSSARY_TERM_EVIDENCE__IMAGE_KEY
    on glossary_term_evidence(image_key);

create table glossary_term_translation_proposal (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    glossary_id bigint(20) not null,
    tm_text_unit_id bigint(20) not null,
    locale_tag varchar(64) not null,
    proposed_target mediumtext not null,
    proposed_target_comment varchar(1024) DEFAULT NULL,
    note varchar(2048) DEFAULT NULL,
    status varchar(32) not null default 'PENDING',
    reviewer_note varchar(2048) DEFAULT NULL,
    primary key (id)
);

alter table glossary_term_translation_proposal
    add constraint FK__GLOSSARY_TERM_TRANSLATION_PROPOSAL__GLOSSARY
        foreign key (glossary_id) references glossary (id);

alter table glossary_term_translation_proposal
    add constraint FK__GLOSSARY_TERM_TRANSLATION_PROPOSAL__TM_TEXT_UNIT
        foreign key (tm_text_unit_id) references tm_text_unit (id);

create index I__GLOSSARY_TERM_TRANSLATION_PROPOSAL__GLOSSARY__STATUS
    on glossary_term_translation_proposal(glossary_id, status);

create index I__GLOSSARY_TERM_TRANSLATION_PROPOSAL__TM_TEXT_UNIT__ID
    on glossary_term_translation_proposal(tm_text_unit_id);

alter table repository
    add column hidden bit not null default false;

alter table repository_aud
    add column hidden bit not null default false;

update repository r
join glossary g on g.backing_repository_id = r.id
set r.hidden = false
where r.hidden = true;
