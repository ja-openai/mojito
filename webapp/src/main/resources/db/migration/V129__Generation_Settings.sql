create table if not exists generation_settings (
    id bigint not null primary key,
    excluded_locale_tags_json longtext not null
);

insert into generation_settings (id, excluded_locale_tags_json)
    select 1, '[]' from (select 1 as seed) initial_settings
    where not exists (select 1 from generation_settings where id = 1);

create table if not exists repository_generation_settings (
    repository_id bigint not null primary key,
    excluded_locale_tags_json longtext not null
);
