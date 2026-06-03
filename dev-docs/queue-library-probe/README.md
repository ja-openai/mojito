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
wakeup and scheduled-maintenance tests without copying their sources. Its extra
boundary test checks that queue classes come from exactly one ordinary JAR,
byte-for-byte identical to the engine artifact just built, and that Mojito business
classes, Quartz, AspectJ and Hibernate ORM are absent. Spring ORM and JPA APIs
remain engine dependencies to preserve its transaction-manager compatibility
checks; these JDBC tests do not exercise the Hibernate/JPA manager path. The consumer alone
selects the two existing V109 scripts as test resources and installs schemas
explicitly in disposable databases. No migrations or application resources are
packaged in the engine. This is not a Flyway adoption/upgrade test.

Without the Testcontainers flag, database cases skip; do not report that run as
the full consumer proof. Test reports are in `consumer/target/surefire-reports`.
The existing database CI job runs these separate builds too; adding them does not
claim remote CI has passed. No automatic Surefire retries are configured. For
dependency inspection and local test formatting:

```sh
mvn -f dev-docs/queue-library-probe/engine/pom.xml dependency:tree
mvn -f dev-docs/queue-library-probe/consumer/pom.xml spotless:apply
```

This probe does not move Mojito's admission, PollableTask, blob, lineage or Quartz
integration into the library. It does not establish API stability, release
licensing/provenance, production capacity, JPA enlistment compatibility or safe
migration adoption. Those remain in `../design/async-job-queue-library.md` and the
queue review ledger. A production module still needs an agreed extraction and
host wiring plan.
