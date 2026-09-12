# Isolated Queue Packaging Probe

This is an opt-in build experiment, not the production module or an OSS release.
Neither POM inherits Mojito's parent or participates in its reactor. Nothing is
moved, copied into a second maintained implementation, deployed or enabled.
The engine compiles the original queue source directory directly with javac,
without webapp output, AspectJ, frontend build or Mojito application dependencies.

Run from the repository root in **two separate Maven invocations**:

```sh
mvn -f dev-docs/queue-library-probe/engine/pom.xml clean install
mvn -f dev-docs/queue-library-probe/consumer/pom.xml clean test -Dmojito.asyncJobQueue.testcontainers=true
```

`install` writes only the disposable `example.queue.probe:queue-engine-probe`
artifact to the local Maven cache; neither POM has a deployment target and both
set `maven.deploy.skip=true`. The second build resolves its ordinary JAR and POM,
not engine classes from a shared reactor. Rebuild the engine before every probe
after source edits. Both POMs intentionally pin the current Java 21/Spring Boot
baseline, not a claimed compatibility matrix.

The consumer reuses the existing external-package execution/inspection, optional
wakeup and scheduled-maintenance tests without copying their sources. The execution
contract includes a direct public `AsyncJobPermanentFailureException` from a handler
on MySQL and PostgreSQL, preserving payload and reporting FAILED at attempt one.
Its boundary tests check every packaged queue class, including private/nested
classes, for exactly one classpath resource and the same ordinary-JAR code origin,
and compare the resolved JAR byte-for-byte with the engine artifact just built.
They also require Mojito business classes, Quartz, AspectJ and (in the default lane)
Hibernate ORM to be absent.
An isolated duplicate-coordinator JAR must fail the boundary check even though
`AsyncJobStore` still has a single origin. Classes are inspected without static
initialization; this is classpath provenance, not a security sandbox or a ban on
the consumer's Mockito test instrumentation.
Spring ORM and JPA APIs remain engine dependencies to preserve its
transaction-manager compatibility checks. The consumer alone
selects the two existing V109 scripts as test resources and installs schemas
explicitly in disposable databases. No migrations or application resources are
packaged in the engine. This is not a Flyway adoption/upgrade test.

An optional **test-only** JPA host lane consumes the same engine JAR:

```sh
mvn -f dev-docs/queue-library-probe/consumer/pom.xml -Pjpa spotless:check clean test
# Include the same host contracts on MySQL 8.4 and PostgreSQL 16 (requires Docker):
mvn -f dev-docs/queue-library-probe/consumer/pom.xml -Pjpa spotless:check clean test -Dmojito.asyncJobQueue.testcontainers=true
```

It adds Hibernate ORM and HSQL only to the consumer's test classpath, with a tiny
host entity and explicit `JpaTransactionManager`/`TransactionTemplate` wiring.
Six parameterized tests exercise public queue bootstrap and enqueue: physical JDBC isolation
and resource suspension/restoration, independent queue commit after host rollback,
read-only host suspension, real INSERT rollback without poisoning host commit,
worker-owned business transactions, and both datasource mismatch guards. No
Mojito entities, AspectJ advice or package-private enqueue primitive are used.
HSQL always runs; without opt-in the six MySQL and six PostgreSQL cases skip.
Each opted-in real-database case owns a fresh container, explicitly installs its
queue DDL fixture, and closes the host context/factory and container even after
setup or assertion failure. This favors isolation over container-startup speed.
The host uses `HibernateJpaDialect` and `DELAYED_ACQUISITION_AND_HOLD`; this is not
an arbitrary provider/version compatibility matrix. The [native PostgreSQL JPA run](../design/async-job-queue-review.md#independent-jar-jpa-database-lanes-2026-09-12-utc)
and [native MySQL 8.0.43 run](../design/async-job-queue-review.md#native-mysql-independent-jpa-host-2026-09-12-utc)
each verify these six contracts plus both ordinary-JAR boundary tests. The same
eight checks also pass on [native MySQL 8.4.11](../design/async-job-queue-review.md#native-mysql-84-jpa-transactions-2026-09-12-utc)
after a fresh engine build and separate JPA/lean controls. This is macOS-native
JPA evidence, not the complete configured Docker/Linux or lean real-DB lane.
Public enqueue deliberately commits independently, not atomically with host work.

Always use `clean test` when switching lanes. The boundary test requires both
Hibernate and the optional test class in JPA mode, and rejects both in the lean
lane; it must not pass because stale compiled classes survived a profile switch.

Without the Testcontainers flag, database cases skip; do not report that run as
the full consumer proof. Test reports are in `consumer/target/surefire-reports`.
The existing database CI job runs the engine, lean real-DB consumer and opted-in
HSQL/MySQL/PostgreSQL JPA consumer separately; a workflow regression requires
the JPA profile, clean build, real-database opt-in and zero automatic reruns together.
Configuring those steps does not
claim remote CI has passed. No automatic Surefire retries are configured. For
dependency inspection and local test formatting:

```sh
mvn -f dev-docs/queue-library-probe/engine/pom.xml dependency:tree
mvn -f dev-docs/queue-library-probe/consumer/pom.xml spotless:apply
```

This probe does not move Mojito's admission, PollableTask, blob, lineage or Quartz
integration into the library. It does not establish API stability, release
licensing/provenance, production capacity, atomic host/queue enlistment or safe
migration adoption. Those remain in `../design/async-job-queue-library.md` and the
queue review ledger. A production module still needs an agreed extraction and
host wiring plan.
