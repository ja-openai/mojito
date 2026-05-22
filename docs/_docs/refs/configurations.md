---
layout: doc
title:  "Configurations"
date:   2016-02-17 15:25:25 -0800
categories: refs
permalink: /docs/refs/configurations/
---
## Configuration Location

{{ site.mojito_green }} configuration loaded from the following files in order:

    classpath:/application.properties                    # default config
    /usr/local/etc/mojito/cli/application.properties     # override config for mojito cli
    /usr/local/etc/mojito/webapp/application.properties  # override config for mojito webapp

To override default configurations of {{ site.mojito_green }}, add them in

    /usr/local/etc/mojito/cli/application.properties     # for mojito cli
    /usr/local/etc/mojito/webapp/application.properties  # for mojito webapp

If you want to use different path to store the override configuration, you can specify the following extra parameter when you start {{ site.mojito_green }} server and when you run {{ site.mojito_green }} CLI.  For example,

    -Dspring.config.location=file:/${YOUR_PATH}/application.properties


## Database Configuration

The default database configuration of {{ site.mojito_green }} is in-memory HSQL database.

    flyway.enabled=false
    spring.jpa.database=HSQL
    spring.jpa.database-platform=org.hibernate.dialect.HSQLDialect
    spring.jpa.hibernate.ddl-auto=update
    spring.datasource.initialize=true
    spring.datasource.data=classpath:/db/hsql/data.sql


You can override the database configuration with PostgreSQL.

[Install PostgreSQL](https://www.postgresql.org/download/) and then create a database for {{ site.mojito_green }}
(with Brew: `brew install postgresql@16`).

Connect to PostgreSQL as a superuser

    psql postgres

Create user `${DB_USERNAME}` with `${DB_PASSWORD}`

    CREATE USER ${DB_USERNAME} WITH PASSWORD '${DB_PASSWORD}';

Create database `${DB_NAME}` and give `${DB_USERNAME}` full access to the database

    CREATE DATABASE ${DB_NAME} OWNER ${DB_USERNAME};
    GRANT ALL PRIVILEGES ON DATABASE ${DB_NAME} TO ${DB_USERNAME};

Configure {{ site.mojito_green }} to use PostgreSQL. When using PostgreSQL, Flyway must be turned on.

    spring.profiles.active=postgres
    spring.flyway.enabled=true
    spring.jpa.defer-datasource-initialization=false
    spring.flyway.clean-disabled=true
    l10n.flyway.clean=false
    spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
    spring.jpa.hibernate.ddl-auto=none
    spring.datasource.url=jdbc:postgresql://localhost:5432/${DB_NAME}
    spring.datasource.username=${DB_USERNAME}
    spring.datasource.password=${DB_PASSWORD}
    spring.datasource.driverClassName=org.postgresql.Driver
    
    l10n.org.quartz.jobStore.useProperties=true
    l10n.org.quartz.scheduler.instanceId=AUTO
    l10n.org.quartz.jobStore.isClustered=true
    l10n.org.quartz.threadPool.threadCount=10
    l10n.org.quartz.jobStore.class=org.quartz.impl.jdbcjobstore.JobStoreTX
    l10n.org.quartz.jobStore.driverDelegateClass=org.quartz.impl.jdbcjobstore.PostgreSQLDelegate
    l10n.org.quartz.jobStore.dataSource=myDS
    l10n.org.quartz.dataSource.myDS.driver=org.postgresql.Driver
    l10n.org.quartz.dataSource.myDS.URL=jdbc:postgresql://localhost:5432/${DB_NAME}
    l10n.org.quartz.dataSource.myDS.user=${DB_USERNAME}
    l10n.org.quartz.dataSource.myDS.password=${DB_PASSWORD}
    l10n.org.quartz.dataSource.myDS.maxConnections=12
    l10n.org.quartz.dataSource.myDS.validationQuery=select 1


## Server Configuration

The default server configuration of {{ site.mojito_green }} to run on port 8080.

	server.port=8080


### Project Request Configuration

The project request configuration is for the offline translation requests.  These settings define where {{ site.mojito_green }} stores xliff files.

By default, {{ site.mojito_green }} uses local file system to manage xliff files.

    l10n.dropExporter.type=FILE_SYSTEM
    l10n.fileSystemDropExporter.basePath=(Java system property for java.io.tmpdir)/fileSystemDropExporter

Let's say that `java.io.tmpdir` is `/tmp`.  When you create a new project request for a repository, {{ site.mojito_green }} generates the following directories.

    /tmp/fileSystemDropExporter/<repository name>/<project name>
        |-> Imported Files
        |-> Localized Files
        |-> Queries
        |-> Quotes
        |-> Source Files
            |-> fr-FR_mm-dd-yy.xliff
            |-> ja-JP_mm-dd-yy.xliff

{{ site.mojito_green }} exports xliff files in `Source Files` directory.  You should give them to the translators to translate.

When translators are done, translated xliff files should be put in the `Localized Files` directory.  {{ site.mojito_green }} imports xliff files from this directory.

### Database Authentication

The default user authentication setting in {{ site.mojito_green }} is to use database.  User information is stored in database.  {{ site.mojito_green }} initially is set up with one default user `admin/ChangeMe`.  You can override the default user settings.  These values are only respected on initial bootstrapping.

    l10n.security.authenticationType=DATABASE
    l10n.bootstrap.defaultUser.username=admin
    l10n.bootstrap.defaultUser.password=ChangeMe

With database authentication, {{ site.mojito_green }} users can be added, updated (with new password) and deleted using {{ site.mojito_green }} CLI.

    # add user - enter password when promted
    mojito user-create  --username ${USERNAME} --password --surname ${SURNAME} --given-name ${GIVEN_NAME} --common-name ${COMMON_NAME}

    # update password - enter password when promted
    mojito user-update --username ${USERNAME} --password

    # delete user
    mojito user-delete --username ${USERNAME}   


### LDAP Authentication

You can override the user authentication setting to use an external LDAP server. The server and port should be included in `l10n.security.ldap.url`.

    l10n.security.authenticationType=LDAP
    l10n.security.ldap.url=ldap://${HOST}:${PORT}/${ROOT}
    l10n.security.ldap.root=${ROOT}
    l10n.security.ldap.userSearchBase=${USER_SEARCH_BASE}
    l10n.security.ldap.userSearchFilter=${USER_SEARCH_FILTER}
    l10n.security.ldap.groupSearchBase=${GROUP_SEARCH_BASE}
    l10n.security.ldap.groupSearchFilter=${GROUP_SEARCH_FILTER}
    l10n.security.ldap.groupRoleAttribute=${GROUP_ROLE_ATTR}
    l10n.security.ldap.managerDn=${MANAGER_DN}
    l10n.security.ldap.managerPassword=${MANAGER_PASSWORD}


## CLI Configuration

The default CLI configuration of {{ site.mojito_green }} is to connect to [http://localhost:8080](http://localhost:8080) with admin user.

    l10n.resttemplate.host=localhost
    l10n.resttemplate.port=8080
    l10n.resttemplate.scheme=http
    l10n.resttemplate.authentication.credentialProvider=CONFIG
    l10n.resttemplate.authentication.username=admin
    l10n.resttemplate.authentication.password=ChangeMe

If you want to authenticate the user running CLI on command-line, set the following configuration to prompt the user for the password.

    l10n.resttemplate.authentication.credentialProvider=CONSOLE

Please note that the the username defaults to the current user (Java system property for user.name) running the CLI instead of `admin`.  Before updating this configuration, it is strongly recommended to add usernames of users that would run the CLI as described in [Database Authentication]({{ site.url }}/docs/refs/configurations/#database-authentication).

You can override Java system property for user.name with `-Duser.name=admin`.  For example, to run {{ site.mojito_green }} CLI as `admin` user, enter the following:

    java -Duser.name=admin -jar mojito-cli-<version>.jar <cli-commands>

### CLI Stateless Auth (MSAL)

The CLI can authenticate with Azure AD using MSAL in a stateless mode. Choose one of the providers and set the corresponding properties.

Common flags:

```
l10n.resttemplate.authentication-mode=STATELESS
# OR legacy: l10n.resttemplate.authentication-mode=STATEFUL
```

Providers and properties:

- MSAL_DEVICE_CODE (interactive on the terminal)
  - `l10n.resttemplate.stateless.provider=MSAL_DEVICE_CODE`
  - `l10n.resttemplate.stateless.msal.authority=https://login.microsoftonline.com/<tenant-id>`
  - `l10n.resttemplate.stateless.msal.client-id=<expose-api-client-id>`
  - `l10n.resttemplate.stateless.msal.scopes=api://<audience>/<scope>`

- MSAL_BROWSER_CODE (interactive via system browser)
  - `l10n.resttemplate.stateless.provider=MSAL_BROWSER_CODE`
  - same properties as device code

- MSAL_CLIENT_CREDENTIALS (non-interactive; application permissions)
  - `l10n.resttemplate.stateless.provider=MSAL_CLIENT_CREDENTIALS`
  - `l10n.resttemplate.stateless.msal.authority=https://login.microsoftonline.com/<tenant-id>`
  - `l10n.resttemplate.stateless.msal.client-id=<application-client-id>`
  - `l10n.resttemplate.stateless.msal.client-secret=<client-credential-client-secret-id>`
  - `l10n.resttemplate.stateless.msal.scopes=<Application ID URI>/.default`

### CLI Header Auth (e.g., Cloudflare Access)

Configure the CLI to attach static headers (such as Cloudflare Access credentials) to each request
instead of performing the legacy form login or MSAL flows.

```
l10n.resttemplate.authentication-mode=HEADER
l10n.resttemplate.header.headers.CF-Access-Client-Id=<client-id>
l10n.resttemplate.header.headers.CF-Access-Client-Secret=<client-secret>
```

Add additional headers as needed by appending more `headers.<Name>=<value>` properties. Values can
also be provided through environment variables using Spring Boot's relaxed binding, for example
`L10N_RESTTEMPLATE_HEADER_HEADERS_CF-ACCESS-CLIENT-SECRET`.
