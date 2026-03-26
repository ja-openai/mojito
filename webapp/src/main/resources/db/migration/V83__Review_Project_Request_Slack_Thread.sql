create table review_project_request_slack_thread (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    review_project_request_id bigint(20) not null,
    slack_client_id varchar(255) not null,
    slack_channel_id varchar(64) not null,
    thread_ts varchar(64) not null,
    primary key (id)
);

alter table review_project_request_slack_thread
    add constraint FK__RPR_SLACK_THREAD__REQUEST
        foreign key (review_project_request_id) references review_project_request (id);

create unique index UK__RPR_SLACK_THREAD__REQUEST_ID
    on review_project_request_slack_thread(review_project_request_id);
