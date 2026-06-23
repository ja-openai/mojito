create table review_project_text_unit_suggestion (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    review_project_text_unit_id bigint(20) not null,
    source varchar(64) not null,
    target longtext not null,
    previous_target longtext DEFAULT NULL,
    notes varchar(4000) DEFAULT NULL,
    version bigint(20) DEFAULT 0,
    created_by_user_id bigint(20) DEFAULT NULL,
    last_modified_by_user_id bigint(20) DEFAULT NULL,
    primary key (id)
);

alter table review_project_text_unit_suggestion
    add constraint FK__RPTUS__REVIEW_PROJECT_TEXT_UNIT
        foreign key (review_project_text_unit_id) references review_project_text_unit (id);

alter table review_project_text_unit_suggestion
    add constraint FK__RPTUS__CREATED_BY_USER
        foreign key (created_by_user_id) references user (id);

alter table review_project_text_unit_suggestion
    add constraint FK__RPTUS__LAST_MODIFIED_BY_USER
        foreign key (last_modified_by_user_id) references user (id);

create unique index UK__REVIEW_PROJECT_TEXT_UNIT_SUGGESTION__TEXT_UNIT
    on review_project_text_unit_suggestion(review_project_text_unit_id);
