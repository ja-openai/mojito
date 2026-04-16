create table translation_incident (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    status varchar(32) not null default 'OPEN',
    resolution varchar(64) not null,
    lookup_resolution_status varchar(64) not null,
    locale_resolution_strategy varchar(64) not null,
    locale_used_fallback bit not null,
    repository_name varchar(255) DEFAULT NULL,
    string_id varchar(1024) not null,
    observed_locale varchar(64) not null,
    resolved_locale varchar(64) DEFAULT NULL,
    resolved_locale_id bigint(20) DEFAULT NULL,
    reason longtext not null,
    source_reference varchar(2048) DEFAULT NULL,
    lookup_candidate_count int not null,
    selected_tm_text_unit_id bigint(20) DEFAULT NULL,
    selected_tm_text_unit_current_variant_id bigint(20) DEFAULT NULL,
    selected_tm_text_unit_variant_id bigint(20) DEFAULT NULL,
    selected_asset_path varchar(1024) DEFAULT NULL,
    selected_source longtext DEFAULT NULL,
    selected_target longtext DEFAULT NULL,
    selected_target_comment longtext DEFAULT NULL,
    selected_translation_status varchar(64) DEFAULT NULL,
    selected_included_in_localized_file bit DEFAULT NULL,
    selected_can_reject bit DEFAULT NULL,
    review_project_id bigint(20) DEFAULT NULL,
    review_project_request_id bigint(20) DEFAULT NULL,
    review_project_name varchar(255) DEFAULT NULL,
    review_project_link varchar(1024) DEFAULT NULL,
    review_project_confidence varchar(32) DEFAULT NULL,
    review_project_confidence_score int DEFAULT NULL,
    translation_author_username varchar(255) DEFAULT NULL,
    reviewer_username varchar(255) DEFAULT NULL,
    owner_username varchar(255) DEFAULT NULL,
    translation_author_slack_mention varchar(255) DEFAULT NULL,
    reviewer_slack_mention varchar(255) DEFAULT NULL,
    owner_slack_mention varchar(255) DEFAULT NULL,
    slack_destination_source varchar(64) DEFAULT NULL,
    slack_client_id varchar(255) DEFAULT NULL,
    slack_channel_id varchar(255) DEFAULT NULL,
    slack_thread_ts varchar(255) DEFAULT NULL,
    slack_can_send bit DEFAULT NULL,
    slack_note longtext DEFAULT NULL,
    slack_draft longtext DEFAULT NULL,
    lookup_candidates_json longtext DEFAULT NULL,
    review_project_candidates_json longtext DEFAULT NULL,
    reject_audit_comment longtext DEFAULT NULL,
    reject_audit_comment_id bigint(20) DEFAULT NULL,
    rejected_by_username varchar(255) DEFAULT NULL,
    rejected_at datetime DEFAULT NULL,
    closed_at datetime DEFAULT NULL,
    closed_by_username varchar(255) DEFAULT NULL,
    primary key (id)
);

create index IDX__TRANSLATION_INCIDENT__STATUS__CREATED_DATE
    on translation_incident(status, created_date desc);

create index IDX__TRANSLATION_INCIDENT__RESOLUTION__CREATED_DATE
    on translation_incident(resolution, created_date desc);

create index IDX__TRANSLATION_INCIDENT__STRING_ID
    on translation_incident(string_id(255));

create index IDX__TRANSLATION_INCIDENT__SELECTED_TM_TEXT_UNIT_ID
    on translation_incident(selected_tm_text_unit_id);
