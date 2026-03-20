create table review_automation_run (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    review_automation_id bigint(20) not null,
    trigger_source varchar(32) not null,
    requested_by_user_id bigint(20) default null,
    status varchar(32) not null,
    started_at datetime default null,
    finished_at datetime default null,
    feature_count int not null default 0,
    created_project_request_count int not null default 0,
    created_project_count int not null default 0,
    created_locale_count int not null default 0,
    skipped_locale_count int not null default 0,
    errored_locale_count int not null default 0,
    error_message varchar(4000) default null,
    primary key (id)
);

alter table review_automation_run
    add constraint FK__REVIEW_AUTOMATION_RUN__AUTOMATION
        foreign key (review_automation_id) references review_automation (id);

alter table review_automation_run
    add constraint FK__REVIEW_AUTOMATION_RUN__USER
        foreign key (requested_by_user_id) references user (id);

create index I__REVIEW_AUTOMATION_RUN__CREATED_DATE
    on review_automation_run(created_date);

create index I__REVIEW_AUTOMATION_RUN__AUTOMATION__ID
    on review_automation_run(review_automation_id);

create index I__REVIEW_AUTOMATION_RUN__REQUESTED_BY_USER__ID
    on review_automation_run(requested_by_user_id);
