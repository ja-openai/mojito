create table user_preferences (
    id bigint(20) not null auto_increment,
    created_date datetime default null,
    last_modified_date datetime default null,
    user_id bigint(20) not null,
    preferences_json longtext not null,
    primary key (id),
    constraint FK__USER_PREFERENCES__USER__ID
        foreign key (user_id) references user(id) on delete cascade
);

create unique index UK__USER_PREFERENCES__USER__ID on user_preferences(user_id);
