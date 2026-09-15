"""Fail closed on missing, skipped or retried required queue database contracts."""

import argparse
from collections import Counter
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


QUEUE_PACKAGE = "com.box.l10n.mojito.queue."
CONSUMER_PACKAGE = "example.queue."
APPLICATION = {
    "JdbcAsyncJobStoreDatabaseIntegrationTest": {"": 20},
    "JdbcAsyncJobStorePoolIntegrationTest": {"MYSQL": 2, "POSTGRESQL": 2},
    "JdbcAsyncJobStoreNetworkIntegrationTest": {"MYSQL": 5, "POSTGRESQL": 5},
    "JdbcAsyncJobStoreDatabaseRestartIntegrationTest": {"MYSQL": 2, "POSTGRESQL": 2},
    "JdbcAsyncJobStoreTimezoneIntegrationTest": {
        "MYSQL, serverPrepared=false": 7,
        "MYSQL, serverPrepared=true": 7,
        "POSTGRESQL, serverPrepared=false": 7,
        "POSTGRESQL, serverPrepared=true": 7,
    },
    "AsyncJobQueueJpaTransactionIntegrationTest": {
        "HSQL": 35, "MYSQL": 35, "POSTGRESQL": 35,
    },
    "JdbcPostgresAsyncJobQueueWakeupListenerDatabaseIntegrationTest": {"": 3},
    "AsyncJobQueueProcessCrashIntegrationTest": {"MYSQL": 2, "POSTGRESQL": 2},
    "AssetLocalizeAsyncJobOutputRetryIntegrationTest": {"": 17},
}
CONSUMER = {
    "AsyncJobQueueExternalBootstrapTest": {"": 7},
    "AsyncJobQueueExternalMaintenanceTest": {"": 5},
    "AsyncJobQueueExternalWakeupTest": {"": 4},
    "QueueJarBoundaryTest": {"": 2},
}
LANES = {
    "application": {
        **{QUEUE_PACKAGE + name: groups for name, groups in APPLICATION.items()},
        "com.box.l10n.mojito.AsyncJobQueueApplicationMigrationTest": {"8.0": 2, "8.4": 2},
    },
    "consumer": {CONSUMER_PACKAGE + name: groups for name, groups in CONSUMER.items()},
    "jpa-consumer": {
        **{CONSUMER_PACKAGE + name: groups for name, groups in CONSUMER.items()},
        CONSUMER_PACKAGE + "QueueJpaConsumerTest": {"hsql": 6, "mysql": 6, "postgresql": 6},
    },
}
OPTIONAL_SKIPS = {
    (
        QUEUE_PACKAGE + "JdbcAsyncJobStoreDatabaseIntegrationTest",
        "runtimePerformanceSmokeRunsAgainstRealDatabases",
    ),
}
FAILED_TAGS = {"failure", "error", "flakyFailure", "flakyError", "rerunFailure", "rerunError"}


def verify_reports(directory, lane):
    """Validate fresh Surefire XML, not database identity or production capacity.

    Per-parameter minimum counts prevent a missing database lane or a zero-test
    BeforeClass assumption from passing. New tests may be added without lowering
    the existing floor; update it deliberately when the required matrix changes.
    CI must clean the report directory before Maven, since XML cannot prove freshness.
    """
    passed = skipped = 0
    for suite, expected_groups in LANES[lane].items():
        path = Path(directory) / f"TEST-{suite}.xml"
        try:
            root = ET.parse(path).getroot()
        except (OSError, ET.ParseError) as error:
            raise ValueError(f"{suite}: missing or unreadable Surefire report") from error
        if root.tag != "testsuite" or root.get("name") != suite:
            raise ValueError(f"{suite}: wrong report identity")
        cases = root.findall("testcase")
        actual_skips = sum(case.find("skipped") is not None for case in cases)
        for attribute, expected in (
            ("tests", len(cases)), ("skipped", actual_skips),
            ("failures", 0), ("errors", 0), ("flakes", 0),
        ):
            try:
                value = int(root.get(attribute, "0" if attribute == "flakes" else ""))
            except ValueError as error:
                raise ValueError(f"{suite}: invalid {attribute} count") from error
            if value != expected:
                raise ValueError(f"{suite}: unexpected {attribute} count {value}")
        if any(element.tag in FAILED_TAGS for element in root.iter()):
            raise ValueError(f"{suite}: failed or automatically retried test")

        seen = set()
        groups = Counter()
        for case in cases:
            name = case.get("name", "")
            if not name or name in seen or case.get("classname") != suite:
                raise ValueError(f"{suite}: missing, duplicate or misattributed test case")
            seen.add(name)
            group = name.rsplit("[", 1)[1][:-1] if "[" in name and name.endswith("]") else ""
            if group not in expected_groups:
                raise ValueError(f"{suite}: unexpected parameter group {group!r}")
            groups[group] += 1
            if case.find("skipped") is not None:
                if (suite, name) not in OPTIONAL_SKIPS:
                    raise ValueError(f"{suite}: required test skipped: {name}")
                skipped += 1
            else:
                passed += 1
        for group, minimum in expected_groups.items():
            if groups[group] < minimum:
                raise ValueError(
                    f"{suite}: group {group!r} has {groups[group]} tests; need {minimum}"
                )
    return passed, skipped


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("lane", choices=LANES)
    parser.add_argument("reports", type=Path)
    arguments = parser.parse_args()
    try:
        passed, skipped = verify_reports(arguments.reports, arguments.lane)
    except ValueError as error:
        print(f"Queue report gate failed: {error}", file=sys.stderr)
        return 1
    print(
        f"{arguments.lane}: {passed} passed, {skipped} optional benchmark skips; "
        "no required skips or reruns"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
