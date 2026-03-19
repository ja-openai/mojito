create table review_feature (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    name varchar(255) NOT NULL,
    enabled bit not null default true,
    primary key (id)
);

alter table review_feature add constraint UK__REVIEW_FEATURE__NAME unique (name);

create table review_feature_repository (
    review_feature_id bigint(20) NOT NULL,
    repository_id bigint(20) NOT NULL
);

alter table review_feature_repository
    add constraint FK__REVIEW_FEATURE_REPOSITORY__FEATURE
        foreign key (review_feature_id) references review_feature (id);

alter table review_feature_repository
    add constraint FK__REVIEW_FEATURE_REPOSITORY__REPOSITORY
        foreign key (repository_id) references repository (id);

alter table review_feature_repository
    add constraint UK__REVIEW_FEATURE_REPOSITORY__FEATURE_REPOSITORY
        unique (review_feature_id, repository_id);

create index I__REVIEW_FEATURE_REPOSITORY__REPOSITORY
    on review_feature_repository(repository_id);
