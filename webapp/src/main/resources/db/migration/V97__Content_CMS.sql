create table cms_content_project (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    created_by_user_id bigint(20) NOT NULL,
    last_modified_by_user_id bigint(20) NOT NULL,
    project_key varchar(128) NOT NULL,
    name varchar(255) NOT NULL,
    description varchar(1024) DEFAULT NULL,
    enabled bit not null default true,
    repository_id bigint(20) NOT NULL,
    asset_id bigint(20) NOT NULL,
    asset_virtual bit not null default true,
    asset_cms_managed bit not null default true,
    asset_deleted bit not null default false,
    delivery_hint varchar(64) NOT NULL default 'BLOB_CDN',
    last_published_snapshot_version integer not null default 0,
    entity_version bigint(20) NOT NULL default 0,
    primary key (id)
);

alter table cms_content_project
    add constraint UK__CMS_CONTENT_PROJECT__PROJECT_KEY unique (project_key);

alter table cms_content_project
    add constraint FK__CMS_CONTENT_PROJECT__CREATED_BY_USER
        foreign key (created_by_user_id) references user (id);

alter table cms_content_project
    add constraint FK__CMS_CONTENT_PROJECT__LAST_MODIFIED_BY_USER
        foreign key (last_modified_by_user_id) references user (id);

alter table cms_content_project
    add constraint CK__CMS_CONTENT_PROJECT__PROJECT_KEY
        check (regexp_like(project_key, '^[a-z0-9][a-z0-9_-]*$', 'c'));

alter table cms_content_project
    add constraint UK__CMS_CONTENT_PROJECT__ASSET unique (asset_id);

alter table cms_content_project
    add constraint UK__CMS_CONTENT_PROJECT__ID_ASSET unique (id, asset_id);

alter table cms_content_project
    add constraint CK__CMS_CONTENT_PROJECT__DELIVERY_HINT
        check (
            delivery_hint in ('BLOB_CDN', 'STATSIG_DYNAMIC_CONFIG', 'EXPERIENCE_FRAMEWORK')
        );

alter table cms_content_project
    add constraint CK__CMS_CONTENT_PROJECT__LAST_PUBLISHED_SNAPSHOT_VERSION
        check (last_published_snapshot_version >= 0);

alter table cms_content_project
    add constraint CK__CMS_CONTENT_PROJECT__ENTITY_VERSION
        check (entity_version >= 0);

alter table asset
    add constraint UK__ASSET__ID__REPOSITORY_ID unique (id, repository_id);

alter table asset
    add constraint UK__ASSET__ID__REPOSITORY_ID__VIRTUAL
        unique (id, repository_id, `virtual`);

alter table asset
    add column cms_managed bit not null default false;

alter table asset
    add constraint UK__ASSET__ID__REPOSITORY_ID__VIRTUAL__CMS_MANAGED
        unique (id, repository_id, `virtual`, cms_managed);

alter table asset
    add constraint UK__ASSET__ID__REPOSITORY_ID__VIRTUAL__CMS_MANAGED__DELETED
        unique (id, repository_id, `virtual`, cms_managed, deleted);

alter table tm_text_unit
    add constraint UK__TM_TEXT_UNIT__ID__ASSET_ID unique (id, asset_id);

alter table asset
    add constraint CK__ASSET__CMS_MANAGED_VIRTUAL
        check (cms_managed = false or `virtual` = true);

alter table cms_content_project
    add constraint FK__CMS_CONTENT_PROJECT__REPOSITORY
        foreign key (repository_id) references repository (id);

alter table cms_content_project
    add constraint FK__CMS_CONTENT_PROJECT__ASSET
        foreign key (asset_id) references asset (id);

create index I__CMS_CONTENT_PROJECT__LIVE_CMS_ASSET
    on cms_content_project(asset_id, repository_id, asset_virtual, asset_cms_managed, asset_deleted);

alter table cms_content_project
    add constraint CK__CMS_CONTENT_PROJECT__ASSET_VIRTUAL
        check (asset_virtual = true);

alter table cms_content_project
    add constraint CK__CMS_CONTENT_PROJECT__ASSET_CMS_MANAGED
        check (asset_cms_managed = true);

alter table cms_content_project
    add constraint CK__CMS_CONTENT_PROJECT__ASSET_NOT_DELETED
        check (asset_deleted = false);

alter table cms_content_project
    add constraint FK__CMS_CONTENT_PROJECT__LIVE_CMS_ASSET
        foreign key (asset_id, repository_id, asset_virtual, asset_cms_managed, asset_deleted)
        references asset (id, repository_id, `virtual`, cms_managed, deleted);

create index I__CMS_CONTENT_PROJECT__REPOSITORY
    on cms_content_project(repository_id);

create table cms_content_type (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    created_by_user_id bigint(20) NOT NULL,
    last_modified_by_user_id bigint(20) NOT NULL,
    content_project_id bigint(20) NOT NULL,
    type_key varchar(128) NOT NULL,
    name varchar(255) NOT NULL,
    description varchar(1024) DEFAULT NULL,
    schema_version integer not null default 1,
    metadata_schema_json longtext DEFAULT NULL,
    entity_version bigint(20) NOT NULL default 0,
    primary key (id)
);

alter table cms_content_type
    add constraint FK__CMS_CONTENT_TYPE__PROJECT
        foreign key (content_project_id) references cms_content_project (id);

alter table cms_content_type
    add constraint FK__CMS_CONTENT_TYPE__CREATED_BY_USER
        foreign key (created_by_user_id) references user (id);

alter table cms_content_type
    add constraint FK__CMS_CONTENT_TYPE__LAST_MODIFIED_BY_USER
        foreign key (last_modified_by_user_id) references user (id);

alter table cms_content_type
    add constraint UK__CMS_CONTENT_TYPE__PROJECT_TYPE_KEY
        unique (content_project_id, type_key);

alter table cms_content_type
    add constraint CK__CMS_CONTENT_TYPE__TYPE_KEY
        check (regexp_like(type_key, '^[a-z0-9][a-z0-9_-]*$', 'c'));

alter table cms_content_type
    add constraint UK__CMS_CONTENT_TYPE__ID_PROJECT
        unique (id, content_project_id);

alter table cms_content_type
    add constraint CK__CMS_CONTENT_TYPE__SCHEMA_VERSION
        check (schema_version >= 1);

alter table cms_content_type
    add constraint CK__CMS_CONTENT_TYPE__ENTITY_VERSION
        check (entity_version >= 0);

create table cms_content_type_field (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    created_by_user_id bigint(20) NOT NULL,
    last_modified_by_user_id bigint(20) NOT NULL,
    content_type_id bigint(20) NOT NULL,
    field_key varchar(128) NOT NULL,
    name varchar(255) NOT NULL,
    description varchar(1024) DEFAULT NULL,
    field_type varchar(32) NOT NULL default 'TEXT',
    localizable bit not null default true,
    required bit not null default false,
    sort_order integer not null default 0,
    entity_version bigint(20) NOT NULL default 0,
    primary key (id)
);

alter table cms_content_type_field
    add constraint FK__CMS_CONTENT_TYPE_FIELD__TYPE
        foreign key (content_type_id) references cms_content_type (id);

alter table cms_content_type_field
    add constraint FK__CMS_CONTENT_TYPE_FIELD__CREATED_BY_USER
        foreign key (created_by_user_id) references user (id);

alter table cms_content_type_field
    add constraint FK__CMS_CONTENT_TYPE_FIELD__LAST_MODIFIED_BY_USER
        foreign key (last_modified_by_user_id) references user (id);

alter table cms_content_type_field
    add constraint UK__CMS_CONTENT_TYPE_FIELD__TYPE_FIELD_KEY
        unique (content_type_id, field_key);

alter table cms_content_type_field
    add constraint CK__CMS_CONTENT_TYPE_FIELD__FIELD_KEY
        check (regexp_like(field_key, '^[a-z0-9][a-z0-9_-]*$', 'c'));

alter table cms_content_type_field
    add constraint UK__CMS_CONTENT_TYPE_FIELD__ID_TYPE
        unique (id, content_type_id);

alter table cms_content_type_field
    add constraint CK__CMS_CONTENT_TYPE_FIELD__FIELD_TYPE
        check (field_type in ('TEXT', 'ICU_MESSAGE'));

alter table cms_content_type_field
    add constraint CK__CMS_CONTENT_TYPE_FIELD__LOCALIZABLE
        check (localizable = true);

alter table cms_content_type_field
    add constraint CK__CMS_CONTENT_TYPE_FIELD__SORT_ORDER
        check (sort_order >= 0);

alter table cms_content_type_field
    add constraint CK__CMS_CONTENT_TYPE_FIELD__ENTITY_VERSION
        check (entity_version >= 0);

create index I__CMS_CONTENT_TYPE_FIELD__TYPE
    on cms_content_type_field(content_type_id);

create table cms_content_entry (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    created_by_user_id bigint(20) NOT NULL,
    last_modified_by_user_id bigint(20) NOT NULL,
    content_project_id bigint(20) NOT NULL,
    content_type_id bigint(20) NOT NULL,
    entry_key varchar(128) NOT NULL,
    name varchar(255) NOT NULL,
    description varchar(1024) DEFAULT NULL,
    status varchar(32) NOT NULL default 'DRAFT',
    metadata_json longtext DEFAULT NULL,
    entity_version bigint(20) NOT NULL default 0,
    primary key (id)
);

alter table cms_content_entry
    add constraint FK__CMS_CONTENT_ENTRY__PROJECT
        foreign key (content_project_id) references cms_content_project (id);

alter table cms_content_entry
    add constraint FK__CMS_CONTENT_ENTRY__CREATED_BY_USER
        foreign key (created_by_user_id) references user (id);

alter table cms_content_entry
    add constraint FK__CMS_CONTENT_ENTRY__LAST_MODIFIED_BY_USER
        foreign key (last_modified_by_user_id) references user (id);

alter table cms_content_entry
    add constraint FK__CMS_CONTENT_ENTRY__TYPE
        foreign key (content_type_id) references cms_content_type (id);

create index I__CMS_CONTENT_ENTRY__TYPE_PROJECT
    on cms_content_entry(content_type_id, content_project_id);

alter table cms_content_entry
    add constraint FK__CMS_CONTENT_ENTRY__TYPE_PROJECT
        foreign key (content_type_id, content_project_id)
        references cms_content_type (id, content_project_id);

alter table cms_content_entry
    add constraint UK__CMS_CONTENT_ENTRY__PROJECT_ENTRY_KEY
        unique (content_project_id, entry_key);

alter table cms_content_entry
    add constraint CK__CMS_CONTENT_ENTRY__ENTRY_KEY
        check (regexp_like(entry_key, '^[a-z0-9][a-z0-9_-]*$', 'c'));

alter table cms_content_entry
    add constraint UK__CMS_CONTENT_ENTRY__ID_TYPE
        unique (id, content_type_id);

alter table cms_content_entry
    add constraint UK__CMS_CONTENT_ENTRY__ID_TYPE_PROJECT
        unique (id, content_type_id, content_project_id);

alter table cms_content_entry
    add constraint CK__CMS_CONTENT_ENTRY__STATUS
        check (status in ('DRAFT', 'READY', 'ARCHIVED'));

alter table cms_content_entry
    add constraint CK__CMS_CONTENT_ENTRY__ENTITY_VERSION
        check (entity_version >= 0);

create table cms_content_entry_variant (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    created_by_user_id bigint(20) NOT NULL,
    last_modified_by_user_id bigint(20) NOT NULL,
    content_entry_id bigint(20) NOT NULL,
    content_type_id bigint(20) NOT NULL,
    content_project_id bigint(20) NOT NULL,
    variant_key varchar(128) NOT NULL,
    name varchar(255) NOT NULL,
    candidate_group_key varchar(128) DEFAULT NULL,
    status varchar(32) NOT NULL default 'CONTROL',
    control_entry_id bigint(20) DEFAULT NULL,
    metadata_json longtext DEFAULT NULL,
    sort_order integer not null default 0,
    entity_version bigint(20) NOT NULL default 0,
    primary key (id)
);

alter table cms_content_entry_variant
    add constraint FK__CMS_CONTENT_ENTRY_VARIANT__ENTRY
        foreign key (content_entry_id) references cms_content_entry (id);

alter table cms_content_entry_variant
    add constraint FK__CMS_CONTENT_ENTRY_VARIANT__CREATED_BY_USER
        foreign key (created_by_user_id) references user (id);

alter table cms_content_entry_variant
    add constraint FK__CMS_CONTENT_ENTRY_VARIANT__LAST_MODIFIED_BY_USER
        foreign key (last_modified_by_user_id) references user (id);

alter table cms_content_entry_variant
    add constraint FK__CMS_CONTENT_ENTRY_VARIANT__TYPE
        foreign key (content_type_id) references cms_content_type (id);

create index I__CMS_CONTENT_ENTRY_VARIANT__ENTRY_TYPE
    on cms_content_entry_variant(content_entry_id, content_type_id);

create index I__CMS_CONTENT_ENTRY_VARIANT__ENTRY_TYPE_PROJECT
    on cms_content_entry_variant(content_entry_id, content_type_id, content_project_id);

alter table cms_content_entry_variant
    add constraint FK__CMS_CONTENT_ENTRY_VARIANT__ENTRY_TYPE_PROJECT
        foreign key (content_entry_id, content_type_id, content_project_id)
        references cms_content_entry (id, content_type_id, content_project_id);

alter table cms_content_entry_variant
    add constraint UK__CMS_CONTENT_ENTRY_VARIANT__ENTRY_VARIANT_KEY
        unique (content_entry_id, variant_key);

alter table cms_content_entry_variant
    add constraint CK__CMS_CONTENT_ENTRY_VARIANT__VARIANT_KEY
        check (regexp_like(variant_key, '^[a-z0-9][a-z0-9_-]*$', 'c'));

alter table cms_content_entry_variant
    add constraint CK__CMS_CONTENT_ENTRY_VARIANT__CANDIDATE_GROUP_KEY
        check (
            candidate_group_key is null
            or regexp_like(candidate_group_key, '^[a-z0-9][a-z0-9_-]*$', 'c')
        );

alter table cms_content_entry_variant
    add constraint CK__CMS_CONTENT_ENTRY_VARIANT__CANDIDATE_GROUP_REQUIRED
        check (status <> 'CANDIDATE' or candidate_group_key is not null);

alter table cms_content_entry_variant
    add constraint UK__CMS_CONTENT_ENTRY_VARIANT__ID_TYPE
        unique (id, content_type_id);

alter table cms_content_entry_variant
    add constraint UK__CMS_CONTENT_ENTRY_VARIANT__ID_TYPE_PROJECT
        unique (id, content_type_id, content_project_id);

alter table cms_content_entry_variant
    add constraint CK__CMS_CONTENT_ENTRY_VARIANT__STATUS
        check (status in ('CONTROL', 'CANDIDATE', 'ARCHIVED'));

alter table cms_content_entry_variant
    add constraint CK__CMS_CONTENT_ENTRY_VARIANT__SORT_ORDER
        check (sort_order >= 0);

alter table cms_content_entry_variant
    add constraint CK__CMS_CONTENT_ENTRY_VARIANT__ENTITY_VERSION
        check (entity_version >= 0);

alter table cms_content_entry_variant
    add constraint CK__CMS_CONTENT_ENTRY_VARIANT__CONTROL_ENTRY
        check (
            (
                status = 'CONTROL'
                and control_entry_id is not null
                and control_entry_id = content_entry_id
            )
            or (status <> 'CONTROL' and control_entry_id is null)
        );

alter table cms_content_entry_variant
    add constraint UK__CMS_CONTENT_ENTRY_VARIANT__CONTROL_ENTRY
        unique (control_entry_id);

create table cms_content_field_mapping (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    created_by_user_id bigint(20) NOT NULL,
    last_modified_by_user_id bigint(20) NOT NULL,
    content_entry_variant_id bigint(20) NOT NULL,
    content_type_field_id bigint(20) NOT NULL,
    content_type_id bigint(20) NOT NULL,
    content_project_id bigint(20) NOT NULL,
    asset_id bigint(20) NOT NULL,
    tm_text_unit_id bigint(20) NOT NULL,
    entity_version bigint(20) NOT NULL default 0,
    primary key (id)
);

alter table cms_content_field_mapping
    add constraint FK__CMS_CONTENT_FIELD_MAPPING__VARIANT
        foreign key (content_entry_variant_id) references cms_content_entry_variant (id);

alter table cms_content_field_mapping
    add constraint FK__CMS_CONTENT_FIELD_MAPPING__CREATED_BY_USER
        foreign key (created_by_user_id) references user (id);

alter table cms_content_field_mapping
    add constraint FK__CMS_CONTENT_FIELD_MAPPING__LAST_MODIFIED_BY_USER
        foreign key (last_modified_by_user_id) references user (id);

alter table cms_content_field_mapping
    add constraint FK__CMS_CONTENT_FIELD_MAPPING__TYPE_FIELD
        foreign key (content_type_field_id) references cms_content_type_field (id);

alter table cms_content_field_mapping
    add constraint FK__CMS_CONTENT_FIELD_MAPPING__TYPE
        foreign key (content_type_id) references cms_content_type (id);

create index I__CMS_CONTENT_FIELD_MAPPING__VARIANT_TYPE
    on cms_content_field_mapping(content_entry_variant_id, content_type_id);

create index I__CMS_CONTENT_FIELD_MAPPING__VARIANT_TYPE_PROJECT
    on cms_content_field_mapping(content_entry_variant_id, content_type_id, content_project_id);

alter table cms_content_field_mapping
    add constraint FK__CMS_CONTENT_FIELD_MAPPING__VARIANT_TYPE_PROJECT
        foreign key (content_entry_variant_id, content_type_id, content_project_id)
        references cms_content_entry_variant (id, content_type_id, content_project_id);

create index I__CMS_CONTENT_FIELD_MAPPING__FIELD_TYPE
    on cms_content_field_mapping(content_type_field_id, content_type_id);

alter table cms_content_field_mapping
    add constraint FK__CMS_CONTENT_FIELD_MAPPING__FIELD_TYPE
        foreign key (content_type_field_id, content_type_id)
        references cms_content_type_field (id, content_type_id);

alter table cms_content_field_mapping
    add constraint FK__CMS_CONTENT_FIELD_MAPPING__TM_TEXT_UNIT
        foreign key (tm_text_unit_id) references tm_text_unit (id);

create index I__CMS_CONTENT_FIELD_MAPPING__PROJECT_ASSET
    on cms_content_field_mapping(content_project_id, asset_id);

alter table cms_content_field_mapping
    add constraint FK__CMS_CONTENT_FIELD_MAPPING__PROJECT_ASSET
        foreign key (content_project_id, asset_id)
        references cms_content_project (id, asset_id);

create index I__CMS_CONTENT_FIELD_MAPPING__TM_TEXT_UNIT_ASSET
    on cms_content_field_mapping(tm_text_unit_id, asset_id);

alter table cms_content_field_mapping
    add constraint FK__CMS_CONTENT_FIELD_MAPPING__TM_TEXT_UNIT_ASSET
        foreign key (tm_text_unit_id, asset_id)
        references tm_text_unit (id, asset_id);

alter table cms_content_field_mapping
    add constraint UK__CMS_CONTENT_FIELD_MAPPING__VARIANT_FIELD
        unique (content_entry_variant_id, content_type_field_id);

alter table cms_content_field_mapping
    add constraint UK__CMS_CONTENT_FIELD_MAPPING__TM_TEXT_UNIT
        unique (tm_text_unit_id);

alter table cms_content_field_mapping
    add constraint CK__CMS_CONTENT_FIELD_MAPPING__ENTITY_VERSION
        check (entity_version >= 0);

create table cms_content_project_aud (
    id bigint(20) NOT NULL,
    rev integer NOT NULL,
    revtype tinyint DEFAULT NULL,
    revend integer DEFAULT NULL,
    created_by_user_id bigint(20) DEFAULT NULL,
    last_modified_by_user_id bigint(20) DEFAULT NULL,
    project_key varchar(128) DEFAULT NULL,
    name varchar(255) DEFAULT NULL,
    description varchar(1024) DEFAULT NULL,
    enabled bit DEFAULT NULL,
    repository_id bigint(20) DEFAULT NULL,
    asset_id bigint(20) DEFAULT NULL,
    asset_virtual bit DEFAULT NULL,
    asset_cms_managed bit DEFAULT NULL,
    asset_deleted bit DEFAULT NULL,
    delivery_hint varchar(64) DEFAULT NULL,
    entity_version bigint(20) DEFAULT NULL,
    primary key (id, rev)
);

alter table cms_content_project_aud
    add constraint FK__CMS_CONTENT_PROJECT_AUD__REV
        foreign key (rev) references revinfo (rev);

alter table cms_content_project_aud
    add constraint FK__CMS_CONTENT_PROJECT_AUD__REVEND
        foreign key (revend) references revinfo (rev);

create table cms_content_type_aud (
    id bigint(20) NOT NULL,
    rev integer NOT NULL,
    revtype tinyint DEFAULT NULL,
    revend integer DEFAULT NULL,
    created_by_user_id bigint(20) DEFAULT NULL,
    last_modified_by_user_id bigint(20) DEFAULT NULL,
    content_project_id bigint(20) DEFAULT NULL,
    type_key varchar(128) DEFAULT NULL,
    name varchar(255) DEFAULT NULL,
    description varchar(1024) DEFAULT NULL,
    schema_version integer DEFAULT NULL,
    metadata_schema_json longtext DEFAULT NULL,
    entity_version bigint(20) DEFAULT NULL,
    primary key (id, rev)
);

alter table cms_content_type_aud
    add constraint FK__CMS_CONTENT_TYPE_AUD__REV
        foreign key (rev) references revinfo (rev);

alter table cms_content_type_aud
    add constraint FK__CMS_CONTENT_TYPE_AUD__REVEND
        foreign key (revend) references revinfo (rev);

create table cms_content_type_field_aud (
    id bigint(20) NOT NULL,
    rev integer NOT NULL,
    revtype tinyint DEFAULT NULL,
    revend integer DEFAULT NULL,
    created_by_user_id bigint(20) DEFAULT NULL,
    last_modified_by_user_id bigint(20) DEFAULT NULL,
    content_type_id bigint(20) DEFAULT NULL,
    field_key varchar(128) DEFAULT NULL,
    name varchar(255) DEFAULT NULL,
    description varchar(1024) DEFAULT NULL,
    field_type varchar(32) DEFAULT NULL,
    localizable bit DEFAULT NULL,
    required bit DEFAULT NULL,
    sort_order integer DEFAULT NULL,
    entity_version bigint(20) DEFAULT NULL,
    primary key (id, rev)
);

alter table cms_content_type_field_aud
    add constraint FK__CMS_CONTENT_TYPE_FIELD_AUD__REV
        foreign key (rev) references revinfo (rev);

alter table cms_content_type_field_aud
    add constraint FK__CMS_CONTENT_TYPE_FIELD_AUD__REVEND
        foreign key (revend) references revinfo (rev);

create table cms_content_entry_aud (
    id bigint(20) NOT NULL,
    rev integer NOT NULL,
    revtype tinyint DEFAULT NULL,
    revend integer DEFAULT NULL,
    created_by_user_id bigint(20) DEFAULT NULL,
    last_modified_by_user_id bigint(20) DEFAULT NULL,
    content_project_id bigint(20) DEFAULT NULL,
    content_type_id bigint(20) DEFAULT NULL,
    entry_key varchar(128) DEFAULT NULL,
    name varchar(255) DEFAULT NULL,
    description varchar(1024) DEFAULT NULL,
    status varchar(32) DEFAULT NULL,
    metadata_json longtext DEFAULT NULL,
    entity_version bigint(20) DEFAULT NULL,
    primary key (id, rev)
);

alter table cms_content_entry_aud
    add constraint FK__CMS_CONTENT_ENTRY_AUD__REV
        foreign key (rev) references revinfo (rev);

alter table cms_content_entry_aud
    add constraint FK__CMS_CONTENT_ENTRY_AUD__REVEND
        foreign key (revend) references revinfo (rev);

create table cms_content_entry_variant_aud (
    id bigint(20) NOT NULL,
    rev integer NOT NULL,
    revtype tinyint DEFAULT NULL,
    revend integer DEFAULT NULL,
    created_by_user_id bigint(20) DEFAULT NULL,
    last_modified_by_user_id bigint(20) DEFAULT NULL,
    content_entry_id bigint(20) DEFAULT NULL,
    content_type_id bigint(20) DEFAULT NULL,
    content_project_id bigint(20) DEFAULT NULL,
    variant_key varchar(128) DEFAULT NULL,
    name varchar(255) DEFAULT NULL,
    candidate_group_key varchar(128) DEFAULT NULL,
    status varchar(32) DEFAULT NULL,
    control_entry_id bigint(20) DEFAULT NULL,
    metadata_json longtext DEFAULT NULL,
    sort_order integer DEFAULT NULL,
    entity_version bigint(20) DEFAULT NULL,
    primary key (id, rev)
);

alter table cms_content_entry_variant_aud
    add constraint FK__CMS_CONTENT_ENTRY_VARIANT_AUD__REV
        foreign key (rev) references revinfo (rev);

alter table cms_content_entry_variant_aud
    add constraint FK__CMS_CONTENT_ENTRY_VARIANT_AUD__REVEND
        foreign key (revend) references revinfo (rev);

create table cms_content_field_mapping_aud (
    id bigint(20) NOT NULL,
    rev integer NOT NULL,
    revtype tinyint DEFAULT NULL,
    revend integer DEFAULT NULL,
    created_by_user_id bigint(20) DEFAULT NULL,
    last_modified_by_user_id bigint(20) DEFAULT NULL,
    content_entry_variant_id bigint(20) DEFAULT NULL,
    content_type_field_id bigint(20) DEFAULT NULL,
    content_type_id bigint(20) DEFAULT NULL,
    content_project_id bigint(20) DEFAULT NULL,
    asset_id bigint(20) DEFAULT NULL,
    tm_text_unit_id bigint(20) DEFAULT NULL,
    entity_version bigint(20) DEFAULT NULL,
    primary key (id, rev)
);

alter table cms_content_field_mapping_aud
    add constraint FK__CMS_CONTENT_FIELD_MAPPING_AUD__REV
        foreign key (rev) references revinfo (rev);

alter table cms_content_field_mapping_aud
    add constraint FK__CMS_CONTENT_FIELD_MAPPING_AUD__REVEND
        foreign key (revend) references revinfo (rev);

create table cms_publish_snapshot (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    created_by_user_id bigint(20) NOT NULL,
    created_by_username varchar(255) NOT NULL,
    published_at varchar(32) NOT NULL,
    content_project_id bigint(20) NOT NULL,
    snapshot_version integer NOT NULL,
    publish_request_key varchar(128) NOT NULL,
    publish_request_locale_tags longtext NOT NULL,
    publish_request_authoring_sha256 varchar(64) NOT NULL,
    publish_request_package_sha256 varchar(64) NOT NULL,
    status varchar(32) NOT NULL default 'PUBLISHED',
    locale_tags longtext NOT NULL,
    artifact_json longtext NOT NULL,
    artifact_sha256 varchar(64) NOT NULL,
    artifact_byte_size bigint(20) NOT NULL,
    snapshot_signing_key_id varchar(128) NOT NULL,
    snapshot_signature varchar(64) NOT NULL,
    artifact_signature varchar(64) NOT NULL,
    completeness_json longtext NOT NULL,
    primary key (id)
);

alter table cms_publish_snapshot
    add constraint FK__CMS_PUBLISH_SNAPSHOT__CREATED_BY_USER
        foreign key (created_by_user_id) references user (id);

alter table cms_publish_snapshot
    add constraint FK__CMS_PUBLISH_SNAPSHOT__PROJECT
        foreign key (content_project_id) references cms_content_project (id);

alter table cms_publish_snapshot
    add constraint UK__CMS_PUBLISH_SNAPSHOT__PROJECT_VERSION
        unique (content_project_id, snapshot_version);

alter table cms_publish_snapshot
    add constraint UK__CMS_PUBLISH_SNAPSHOT__PROJECT_REQUEST_KEY
        unique (content_project_id, publish_request_key);

alter table cms_publish_snapshot
    add constraint CK__CMS_PUBLISH_SNAPSHOT__SNAPSHOT_VERSION
        check (snapshot_version >= 1);

alter table cms_publish_snapshot
    add constraint CK__CMS_PUBLISH_SNAPSHOT__PUBLISH_REQUEST_KEY
        check (regexp_like(publish_request_key, '^[a-z0-9][a-z0-9_-]*$', 'c'));

alter table cms_publish_snapshot
    add constraint CK__CMS_PUBLISH_SNAPSHOT__PUBLISH_REQUEST_AUTHORING_SHA256
        check (regexp_like(publish_request_authoring_sha256, '^[0-9a-f]{64}$', 'c'));

alter table cms_publish_snapshot
    add constraint CK__CMS_PUBLISH_SNAPSHOT__PUBLISH_REQUEST_PACKAGE_SHA256
        check (regexp_like(publish_request_package_sha256, '^[0-9a-f]{64}$', 'c'));

alter table cms_publish_snapshot
    add constraint CK__CMS_PUBLISH_SNAPSHOT__STATUS
        check (status = 'PUBLISHED');

alter table cms_publish_snapshot
    add constraint CK__CMS_PUBLISH_SNAPSHOT__CREATED_BY_USERNAME
        check (regexp_like(created_by_username, '[^[:space:]]', 'c'));

alter table cms_publish_snapshot
    add constraint CK__CMS_PUBLISH_SNAPSHOT__PUBLISHED_AT
        check (
            regexp_like(
                published_at,
                '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]+)?Z$',
                'c'));

alter table cms_publish_snapshot
    add constraint CK__CMS_PUBLISH_SNAPSHOT__ARTIFACT_SHA256
        check (regexp_like(artifact_sha256, '^[0-9a-f]{64}$', 'c'));

alter table cms_publish_snapshot
    add constraint CK__CMS_PUBLISH_SNAPSHOT__ARTIFACT_BYTE_SIZE
        check (artifact_byte_size >= 0);

alter table cms_publish_snapshot
    add constraint CK__CMS_PUBLISH_SNAPSHOT__SNAPSHOT_SIGNING_KEY_ID
        check (regexp_like(snapshot_signing_key_id, '^[a-z0-9][a-z0-9_-]*$', 'c'));

alter table cms_publish_snapshot
    add constraint CK__CMS_PUBLISH_SNAPSHOT__SNAPSHOT_SIGNATURE
        check (regexp_like(snapshot_signature, '^[0-9a-f]{64}$', 'c'));

alter table cms_publish_snapshot
    add constraint CK__CMS_PUBLISH_SNAPSHOT__ARTIFACT_SIGNATURE
        check (regexp_like(artifact_signature, '^[0-9a-f]{64}$', 'c'));

create table cms_publish_snapshot_seal (
    publish_snapshot_id bigint(20) NOT NULL,
    primary key (publish_snapshot_id)
);

alter table cms_publish_snapshot_seal
    add constraint FK__CMS_PUBLISH_SNAPSHOT_SEAL__SNAPSHOT
        foreign key (publish_snapshot_id) references cms_publish_snapshot (id);

create index I__CMS_PUBLISH_SNAPSHOT__PROJECT_CREATED
    on cms_publish_snapshot(content_project_id, created_date);
