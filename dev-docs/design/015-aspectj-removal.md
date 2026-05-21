# AspectJ Removal Plan

Status: active planning

## Context

Mojito currently uses AspectJ compile-time weaving in `common`, `webapp`, and `cli`. This creates a
build-time dependency on `ajc` and makes behavior depend on cross-cutting annotations and weaving
rather than code visible at the call site.

The target direction is not Spring AOP. Proxy-based AOP keeps hidden control flow, self-invocation
edge cases, and debugger surprises. Mojito should move toward explicit imperative Java, even when
that means repeated boilerplate, because the codebase is increasingly edited by agents and should be
easy to inspect, debug, and mechanically modify.

## Current Inventory

Build wiring:

- Parent `pom.xml` declares `aspectj-maven-plugin.version` and a provided `aspectjrt` dependency.
- `common/pom.xml`, `webapp/pom.xml`, and `cli/pom.xml` run `aspectj-maven-plugin` for `compile`
  and `test-compile`.
- Aspect libraries include `spring-aspects` in `common`, `webapp`, and `cli`, plus
  `spring-security-aspects` in `webapp`.

Framework modes:

- `Application` uses `@EnableSpringConfigured` and
  `@EnableTransactionManagement(mode = AdviceMode.ASPECTJ)`.
- `AsyncConfig` uses `@EnableAsync(mode = AdviceMode.ASPECTJ)`.
- `CachingConfig` uses `@EnableCaching(mode = AdviceMode.ASPECTJ)`.
- `WebSecurityConfig` uses `@EnableGlobalMethodSecurity(..., mode = AdviceMode.ASPECTJ)`.
- `cli.App` uses `@EnableSpringConfigured`.

Annotation usage from the initial scan:

- `39` `@Configurable` usages across common, webapp, and cli.
- `39` `@Pollable` usages in webapp services.
- `17` `@Timed` usages.
- `9` `@StopWatch`, `@RunAs`, and `@JsonRawString` usages combined.
- `@Retryable` remains in `AssetExtractionService` and `TextUnitSearcher`.

Custom AspectJ entry points:

- `PollableAspect` plus `PollableAspectConfig` and `@DeclareError` validations.
- `RunAsAspect` plus `RunAsAspectConfig`.
- `JsonRawStringAspect` plus `JsonRawStringAspectConfig`.
- `StopWatchAspect`.
- `TimedAspect`.

Removed during this workstream:

- The unused custom `@Retry`, `RetryAspect`, and `RetryAspectConfig` path. Existing retry behavior
  uses explicit `RetryTemplate` calls or Spring's `@Retryable`, not the custom annotation.
- `@StopWatch` and `StopWatchAspect`. The four annotated methods now use explicit local
  `Stopwatch` logging.
- `@JsonRawString`, `JsonRawStringAspect`, and the aspect-specific test fixtures. `PollableTask`
  now validates and quotes raw JSON strings directly in its serialized getters.

## Migration Principles

- Prefer plain `try/catch/finally` where the behavior changes business outcome.
- Keep checked exceptions checked. Avoid wrapping and unwrapping just to fit lambda APIs.
- Keep transaction, retry, pollable-task, security, and timing boundaries visible in the method
  where they matter.
- Use small services for operations such as creating tasks, recording metrics, or setting security
  context, but do not hide the main control flow behind annotations, proxies, fake resource scopes,
  or broad lambda runners.
- Accept simple repeated boilerplate when it improves local readability.
- Migrate one topic per commit and preserve behavior before simplifying.

## Workstreams

### Transactions

Replace AspectJ transaction mode with explicit imperative transaction boundaries where behavior
depends on self-invocation, checked exceptions, retries, or nested transaction semantics.

Preferred shape:

```java
public Result importFile(File file) throws IOException {
  return importFileWithTx(file);
}

private Result importFileWithTx(File file) throws IOException {
  TransactionStatus transaction = transactionManager.getTransaction(transactionDefinition);

  try {
    Result result = doImportFile(file);
    transactionManager.commit(transaction);
    return result;
  } catch (IOException | RuntimeException e) {
    transactionManager.rollback(transaction);
    throw e;
  } catch (Error e) {
    transactionManager.rollback(transaction);
    throw e;
  }
}
```

Use `TransactionTemplate` only for simple runtime-exception-only paths where it stays clearer than
manual transaction management.

### Retry

Use explicit retry loops in critical paths, especially when the behavior changes by attempt number.
Keep retry and transaction boundaries visibly close.

Generic retry helpers are acceptable for boring "retry the exact same operation" cases. Avoid
annotation retry for workflows that need cleanup, reloads, repartitioning, persistence-context
clearing, or different behavior after a certain number of attempts.

### Pollable Tasks

Replace `@Pollable` with explicit task lifecycle code. Preserve current behavior first:

- sync versus async execution
- `PollableFuture` return behavior
- parent task and current task semantics
- message arguments
- expected subtask count
- timeout handling
- failure recording and exception propagation

Preferred shape:

```java
PollableTask task = pollableTaskService.createPollableTask(...);

try {
  Result result = doWork(task);
  pollableTaskService.markSuccess(task);
  return result;
} catch (Exception e) {
  pollableTaskService.markFailed(task, e);
  throw e;
}
```

Runtime validation and focused tests should replace the current `@DeclareError` checks.

### Configurable Objects

Remove `@Configurable` injection. Replace it with constructor injection, factories, or explicit
collaborators. Pay special attention to Okapi filters and steps, CLI command helpers, cache jobs,
translation kit classes, and pollable helper classes that are currently created with `new` but
receive Spring dependencies through weaving.

### Security Context

Replace `@RunAs` with explicit security-context changes. Prefer visible restore behavior:

```java
SecurityContext previousContext = securityContextService.runAsSystem();
try {
  return doWork();
} finally {
  securityContextService.restore(previousContext);
}
```

### Timing And Metrics

Replace `@Timed` and `@StopWatch` with explicit timing code. Avoid `try-with-resources` timing
scopes by default; use start-time plus `finally` so stack traces and debugging stay plain.

```java
long startTime = clock.millis();
try {
  return doWork();
} finally {
  timings.record("metric.name", clock.millis() - startTime);
}
```

### JSON Raw Serialization

Replace `@JsonRawString` with explicit DTO or Jackson serialization behavior. The response shape
should not depend on method weaving.

### Build Cleanup

After all usages are gone:

- Remove `aspectj-maven-plugin` from `common`, `webapp`, and `cli`.
- Remove `aspectj-maven-plugin.version`, `aspectjrt`, `aspectjtools`, `spring-aspects`, and
  `spring-security-aspects` where no longer needed.
- Remove obsolete aspect classes and `Aspects.aspectOf(...)` configuration.
- Confirm Maven compile/test no longer invokes `ajc`.

## Validation

For each behavior-changing commit:

- Run narrow tests for touched services.
- Add or update tests that lock down migrated control flow.

Before final AspectJ build cleanup:

- Run `mvn spotless:apply`.
- Run `mvn -pl webapp -Pfrontend test-compile -DskipTests`.
- Run relevant backend unit tests, then the full unit test suite if feasible.
- Run the MySQL-backed suite before considering the migration complete.

Frontend checks are only required if frontend files are touched.
