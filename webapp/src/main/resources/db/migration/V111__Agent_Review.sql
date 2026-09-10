create table agent_review_run (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    request_key varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    input_fingerprint varchar(64) NOT NULL,
    requested_by_user_id bigint(20) NOT NULL,
    review_type varchar(64) NOT NULL,
    team_id bigint(20) NOT NULL,
    repository_ids_json longtext NOT NULL,
    locale_ids_json longtext NOT NULL,
    method_version varchar(128) NOT NULL,
    configuration_version varchar(128) NOT NULL,
    manifest_sha256 varchar(64) NOT NULL,
    status varchar(32) NOT NULL,
    planned_group_count int NOT NULL,
    completed_group_count int NOT NULL,
    failed_group_count int NOT NULL,
    reviewed_item_count int NOT NULL,
    checkpoint_sha256 varchar(64) DEFAULT NULL,
    checkpoint_request_fingerprint varchar(64) DEFAULT NULL,
    revision bigint(20) NOT NULL,
    claim_owner varchar(128) DEFAULT NULL,
    claimed_by_user_id bigint(20) DEFAULT NULL,
    claim_generation bigint(20) NOT NULL,
    lease_expires_at datetime DEFAULT NULL,
    completed_at datetime DEFAULT NULL,
    due_date_offset_days int NOT NULL,
    max_word_count_per_project int NOT NULL,
    assign_translator bit NOT NULL,
    primary key (id)
);

create table agent_review_proposal (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    run_id bigint(20) NOT NULL,
    submission_key varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    finding_id varchar(36) NOT NULL,
    proposal_revision int NOT NULL,
    previous_proposal_id bigint(20) DEFAULT NULL,
    responds_to_feedback_id bigint(20) DEFAULT NULL,
    group_key varchar(128) NOT NULL,
    repository_id bigint(20) NOT NULL,
    locale_id bigint(20) NOT NULL,
    tm_text_unit_id bigint(20) NOT NULL,
    source longtext NOT NULL,
    source_comment longtext DEFAULT NULL,
    baseline_variant_id bigint(20) DEFAULT NULL,
    baseline_target longtext DEFAULT NULL,
    baseline_status varchar(32) DEFAULT NULL,
    baseline_included_in_localized_file bit DEFAULT NULL,
    proposed_target longtext DEFAULT NULL,
    category varchar(32) NOT NULL,
    readiness varchar(32) NOT NULL,
    disposition varchar(32) NOT NULL,
    rationale longtext NOT NULL,
    evidence_json longtext DEFAULT NULL,
    producer_identity varchar(255) NOT NULL,
    verifier_identity varchar(255) DEFAULT NULL,
    verification_rationale longtext DEFAULT NULL,
    integrity_diagnostics longtext DEFAULT NULL,
    incident_id bigint(20) DEFAULT NULL,
    review_project_id bigint(20) DEFAULT NULL,
    review_project_text_unit_id bigint(20) DEFAULT NULL,
    version bigint(20) NOT NULL,
    primary key (id)
);

create table agent_review_feedback (
    response_run_id bigint(20) DEFAULT NULL,
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    proposal_id bigint(20) NOT NULL,
    request_key varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    actor_type varchar(16) NOT NULL,
    actor_user_id bigint(20) NOT NULL,
    actor_identity varchar(255) NOT NULL,
    action varchar(32) NOT NULL,
    original_assessment varchar(32) DEFAULT NULL,
    suggestion_assessment varchar(32) DEFAULT NULL,
    explanation longtext DEFAULT NULL,
    evidence_json longtext DEFAULT NULL,
    follow_up_requested bit NOT NULL,
    responds_to_feedback_id bigint(20) DEFAULT NULL,
    response_proposal_id bigint(20) DEFAULT NULL,
    final_target longtext DEFAULT NULL,
    applied_variant_id bigint(20) DEFAULT NULL,
    primary key (id)
);

create unique index UK__AGENT_REVIEW_RUN__REQUEST on agent_review_run(requested_by_user_id, request_key);
create index I__AGENT_REVIEW_RUN__TEAM on agent_review_run(team_id, created_date);
create unique index UK__AGENT_REVIEW_PROPOSAL__SUBMISSION on agent_review_proposal(run_id, submission_key);
create unique index UK__AGENT_REVIEW_PROPOSAL__REVISION on agent_review_proposal(finding_id, proposal_revision);
create index I__AGENT_REVIEW_PROPOSAL__READY on agent_review_proposal(run_id, readiness, disposition);
create index I__AGENT_REVIEW_PROPOSAL__PROJECT_ROW on agent_review_proposal(review_project_text_unit_id);
create index I__AGENT_REVIEW_PROPOSAL__INCIDENT on agent_review_proposal(incident_id);
create unique index UK__AGENT_REVIEW_FEEDBACK__REQUEST on agent_review_feedback(proposal_id, actor_type, request_key);
create unique index UK__AGENT_REVIEW_FEEDBACK__RESPONSE on agent_review_feedback(responds_to_feedback_id);
create index I__AGENT_REVIEW_FEEDBACK__PENDING on agent_review_feedback(proposal_id, follow_up_requested);
alter table agent_review_proposal add constraint FK__AGENT_REVIEW_PROPOSAL__RUN foreign key (run_id) references agent_review_run(id);
alter table agent_review_feedback add constraint FK__AGENT_REVIEW_FEEDBACK__PROPOSAL foreign key (proposal_id) references agent_review_proposal(id);
