create table review_project_assignment_window (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime default null,
    last_modified_date datetime default null,
    review_project_id bigint(20) not null,
    assigned_translator_user_id bigint(20) default null,
    assigned_translator_username_snapshot varchar(255) default null,
    assigned_at datetime not null,
    accepted_at datetime default null,
    ended_at datetime default null,
    end_reason varchar(32) default null,
    self_reported_minutes int default null,
    self_reported_note varchar(512) default null,
    self_reported_at datetime default null,
    self_reported_by_user_id bigint(20) default null,
    primary key (id)
);

alter table review_project_assignment_window
    add constraint FK__RP_ASSIGNMENT_WINDOW__PROJECT
        foreign key (review_project_id) references review_project (id);

alter table review_project_assignment_window
    add constraint FK__RP_ASSIGNMENT_WINDOW__TRANSLATOR
        foreign key (assigned_translator_user_id) references user (id);

alter table review_project_assignment_window
    add constraint FK__RP_ASSIGNMENT_WINDOW__REPORTED_BY
        foreign key (self_reported_by_user_id) references user (id);

create index I__RP_ASSIGNMENT_WINDOW__PROJECT
    on review_project_assignment_window(review_project_id);

create index I__RP_ASSIGNMENT_WINDOW__TRANSLATOR
    on review_project_assignment_window(assigned_translator_user_id);

create index I__RP_ASSIGNMENT_WINDOW__ASSIGNED_AT
    on review_project_assignment_window(assigned_at);

create index I__RP_ASSIGNMENT_WINDOW__ACCEPTED_AT
    on review_project_assignment_window(accepted_at);

create index I__RP_ASSIGNMENT_WINDOW__ENDED_AT
    on review_project_assignment_window(ended_at);

create table review_project_time_spent_stat (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime default null,
    last_modified_date datetime default null,
    assignment_window_id bigint(20) not null,
    review_project_id bigint(20) not null,
    review_project_request_id bigint(20) default null,
    review_project_request_name varchar(255) default null,
    review_project_type varchar(32) default null,
    review_project_status varchar(32) default null,
    locale_bcp47_tag varchar(255) default null,
    assigned_translator_user_id bigint(20) default null,
    assigned_translator_username varchar(255) default null,
    assignment_window_started_at datetime default null,
    assignment_accepted_at datetime default null,
    assignment_window_ended_at datetime default null,
    assignment_window_end_reason varchar(32) default null,
    project_created_date datetime default null,
    project_due_date datetime default null,
    text_unit_count bigint(20) not null default 0,
    word_count bigint(20) not null default 0,
    decided_count bigint(20) not null default 0,
    decided_word_count bigint(20) not null default 0,
    first_decision_at datetime default null,
    last_decision_at datetime default null,
    raw_decision_span_seconds bigint(20) not null default 0,
    project_span_seconds bigint(20) not null default 0,
    accepted_to_first_decision_seconds bigint(20) default null,
    assigned_to_accepted_seconds bigint(20) default null,
    estimated_active_seconds bigint(20) not null default 0,
    pause_seconds bigint(20) not null default 0,
    pause_count bigint(20) not null default 0,
    pause_ratio double not null default 0,
    self_reported_seconds bigint(20) default null,
    reported_computed_delta_seconds bigint(20) default null,
    reported_computed_ratio double default null,
    reported_missing bit not null default false,
    review_flag varchar(32) not null default 'OK',
    closed_with_pending bit not null default false,
    pending_count_at_close bigint(20) default null,
    pending_word_count_at_close bigint(20) default null,
    close_reason varchar(512) default null,
    finalized_at datetime default null,
    computed_at datetime not null,
    attribution_confidence varchar(32) not null default 'ASSIGNMENT_WINDOW',
    primary key (id)
);

alter table review_project_time_spent_stat
    add constraint FK__RP_TIME_SPENT_STAT__WINDOW
        foreign key (assignment_window_id) references review_project_assignment_window (id);

alter table review_project_time_spent_stat
    add constraint FK__RP_TIME_SPENT_STAT__PROJECT
        foreign key (review_project_id) references review_project (id);

alter table review_project_time_spent_stat
    add constraint FK__RP_TIME_SPENT_STAT__REQUEST
        foreign key (review_project_request_id) references review_project_request (id);

alter table review_project_time_spent_stat
    add constraint FK__RP_TIME_SPENT_STAT__TRANSLATOR
        foreign key (assigned_translator_user_id) references user (id);

create unique index UK__RP_TIME_SPENT_STAT__WINDOW
    on review_project_time_spent_stat(assignment_window_id);

create index I__RP_TIME_SPENT_STAT__PROJECT
    on review_project_time_spent_stat(review_project_id);

create index I__RP_TIME_SPENT_STAT__REQUEST
    on review_project_time_spent_stat(review_project_request_id);

create index I__RP_TIME_SPENT_STAT__TRANSLATOR
    on review_project_time_spent_stat(assigned_translator_user_id);

create index I__RP_TIME_SPENT_STAT__LOCALE
    on review_project_time_spent_stat(locale_bcp47_tag);

create index I__RP_TIME_SPENT_STAT__LAST_DECISION
    on review_project_time_spent_stat(last_decision_at);

create index I__RP_TIME_SPENT_STAT__FINALIZED
    on review_project_time_spent_stat(finalized_at);
