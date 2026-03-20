create table review_automation (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    name varchar(255) NOT NULL,
    enabled bit not null default true,
    cron_expression varchar(255) not null,
    time_zone varchar(100) not null default 'UTC',
    max_word_count_per_project int not null default 2000,
    primary key (id)
);

alter table review_automation add constraint UK__REVIEW_AUTOMATION__NAME unique (name);

create table review_automation_feature (
    review_automation_id bigint(20) NOT NULL,
    review_feature_id bigint(20) NOT NULL
);

alter table review_automation_feature
    add constraint FK__REVIEW_AUTOMATION_FEATURE__AUTOMATION
        foreign key (review_automation_id) references review_automation (id);

alter table review_automation_feature
    add constraint FK__REVIEW_AUTOMATION_FEATURE__FEATURE
        foreign key (review_feature_id) references review_feature (id);

alter table review_automation_feature
    add constraint UK__REVIEW_AUTOMATION_FEATURE__AUTOMATION_FEATURE
        unique (review_automation_id, review_feature_id);

create index I__REVIEW_AUTOMATION_FEATURE__FEATURE
    on review_automation_feature(review_feature_id);
