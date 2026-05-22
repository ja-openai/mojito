---
layout: doc
title:  "Installation and Setup (Spring Boot 3 on master)"
categories: guides
permalink: /docs/guides/install-springboot3/
---

## Installation

No binaries are available for the `Spring Boot 3` at the moment. It should be built from the `master` branch.

Assuming `Java 21` is installed, `./mvnw install -DskipTests` should be enough to build the `jar` files.

For detail instructions on development environment setup, [see here]({{ site.url }}/docs/guides/open-source-contributors/). 

### Using Executable Jars

`Java 21` is required.

Run the Webapp with:

```bash
java -jar mojito-webapp-*-exec.jar
```
Run the CLI with:

```bash
java -jar mojito-cli-*-exec.jar
```

As {{ site.mojito_green }} is based on Spring Boot, it can be [configured](http://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/#boot-features-external-config) in many ways.

One simple solution is to add an `application.properties` next to the `jar`. To use a different location use `--spring.config.additional-location=optional:/path/to/your/application.properties`.

### CLI install script

The server provides an entry point to fetch a `bash` script that downloads the latest CLI from the server and create a bash 
wrapper to easily run the CLI.

It can be called with a one liner to make the bash command available rigth away in the current console. Replace 
`http://localhost:8080` with the actual URL if needed. 

```bash
# bash 4:
source <(curl -L -N -s http://localhost:8080/cli/install.sh)

# bash 3 (mac):
source /dev/stdin <<< "$(curl -L -N -s http://localhost:8080/cli/install.sh)"

# Optional: specify the install directory: 
source <(curl -L -N -s http://localhost:8080/cli/install.sh?installDirectory=mydirectory)

# When Cloudflare Zero Trust protects the endpoint, set the headers:
export L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_ID=<client-id>
export L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_SECRET=<client-secret>
source /dev/stdin <<< "$(curl -L -N -s -H \"CF-Access-Client-Id: ${L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_ID}\" -H \"CF-Access-Client-Secret: ${L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_CLIENT_SECRET}\" http://localhost:8080/cli/install.sh?authMode=CF_SERVICE_TOKEN)"

# Or use a user token issued by Cloudflare Access:
export L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_TOKEN=<token>
source /dev/stdin <<< "$(curl -L -N -s -H \"CF-Access-Token: ${L10N_RESTTEMPLATE_HEADER_HEADERS_CF_ACCESS_TOKEN}\" http://localhost:8080/cli/install.sh?authMode=CF_JWT)"

The install script automatically exports `L10N_RESTTEMPLATE_AUTHENTICATION_MODE=HEADER` when these
headers are present.
```

After that in the current console, `mojito` is available
```bash
mojito -v
```

If the server is running behind a load balancer, use the following setting to make sure the links in the bash script
use the load balancer URL:

```properties
server.forward-headers-strategy=native
```

## Setup

The default setup comes with `HSQL` in-memory database, database authentication and runs on port `8080`.
For production, `PostgreSQL` should be setup. Different types of [authentication](/docs/guides/authentication-springboot3/) are
available too.

On the first Webapp startup, a user: `admin/ChangeMe` is created. This can be customized with configuration, 
see [Manage Users]({{ site.url }}/docs/guides/manage-users/#bootstraping).

### Server port

The port can be changed with the `server.port` property.

### PostgreSQL

[Install PostgreSQL](https://www.postgresql.org/download/) and then create a database for {{ site.mojito_green }}
(with Brew: `brew install postgresql@16`).

Connect to PostgreSQL as a superuser

```sql
psql postgres
```

Create user `${DB_USERNAME}` with `${DB_PASSWORD}`

```sql
CREATE USER ${DB_USERNAME} WITH PASSWORD '${DB_PASSWORD}';
```

Create database `${DB_NAME}` and give `${DB_USERNAME}` full access to the database

```sql
CREATE DATABASE ${DB_NAME} OWNER ${DB_USERNAME};
GRANT ALL PRIVILEGES ON DATABASE ${DB_NAME} TO ${DB_USERNAME};
```

Configure {{ site.mojito_green }} to use PostgreSQL. When using PostgreSQL, Flyway must be turned on and it is strongly
recommended to explicitly disable the "database clean" features ([more info](#database-protection)). 

```properties
spring.profiles.active=postgres
spring.flyway.enabled=true
spring.jpa.defer-datasource-initialization=false
spring.flyway.clean-disabled=true 
l10n.flyway.clean=false
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
l10n.org.quartz.dataSource.myDS.provider=hikaricp
l10n.org.quartz.dataSource.myDS.driver=org.postgresql.Driver
l10n.org.quartz.dataSource.myDS.URL=jdbc:postgresql://localhost:5432/${DB_NAME}
l10n.org.quartz.dataSource.myDS.user=${DB_USERNAME}
l10n.org.quartz.dataSource.myDS.password=${DB_PASSWORD}
l10n.org.quartz.dataSource.myDS.maxConnections=12
l10n.org.quartz.dataSource.myDS.validationQuery=select 1
```

### CLI

The default CLI configuration maps to the server default configuration and allows to access the server without
having to enter credential.

To access a production instance, the server url and port should be configured and it is also common to use the console to enter credential.

```properties
l10n.resttemplate.host=${HOSTNAME}
l10n.resttemplate.port=${PORT}
l10n.resttemplate.authentication.credentialProvider=CONSOLE
```

### Database protection

When Flyway is used for DB migration, the Mojito setting to clean the database and the Flyway built-in setting to prevent
 database cleanup are useful features but it can turn out to be very dangerous if wrong values ever leak to production.

It is strongly recommended to explicitly disable the Mojito cleanup feature (it is disabled by default but may prevent bad 
configuration to propagate) and to configure Flyway to disable cleanup as well (this is not the default settings).

In short, recommended settings are:

```properties
spring.flyway.clean-disabled=true 
l10n.flyway.clean=false
```
An additional protection which is not based on settings is also available. The clean operation can be prevented by
adding a flag in the database using following commands:

```sql
CREATE TABLE flyway_clean_protection(enabled boolean default true);
INSERT INTO flyway_clean_protection (enabled) VALUES (1);
```

Note that this check is optimistic and if for some reason the query fails it will consider that the database not 
 protected. This is just an additional protection in case the settings are missued but you should not rely exclusively
 on it.  
