import datetime as dt
import json
from types import SimpleNamespace
import unittest

import render


def fixture(now=None):
    now = now or dt.datetime.now(dt.timezone.utc)
    return {"validUntil": int((now + dt.timedelta(seconds=250)).timestamp() * 1000), "evidence": {
        "id": "00000000-0000-0000-0000-000000000001",
        "snapshotRunId": "00000000-0000-0000-0000-000000000002",
        "fenceId": "staging-maintenance", "manifestSha256": "a" * 64,
        "destinationRoot": "https://example.blob.core.windows.net/container/root/mblob_migration/v1/",
        "sourceHighWaterId": 100, "reconciliationCursor": 100,
        "reconciledCount": 5, "canonicalCount": 1, "retainedCount": 4, "retainedBytes": 13,
        "reconciledAt": int((now - dt.timedelta(seconds=1)).timestamp() * 1000),
        "status": "RECONCILED", "phase": "COMPLETE", "leaseToken": None, "lastError": None,
    }}


def options(**changes):
    value = dict(phase="batch", database="mojito_replacement_probe", replacement="mblob_replacement_review",
                 backup="mblob_before_review", max_rows=2, max_bytes=100)
    value.update(changes)
    return SimpleNamespace(**value)


class RenderTest(unittest.TestCase):
    def test_prepare_checks_schema_dependencies_and_does_not_rename(self):
        sql = render.render(options(phase="prepare"), fixture())
        self.assertIn("CREATE TABLE `mblob_replacement_review` LIKE mblob", sql)
        self.assertIn("information_schema.key_column_usage", sql)
        self.assertIn("information_schema.view_table_usage", sql)
        self.assertNotIn("RENAME TABLE", sql)
        self.assertNotIn("DROP TABLE", sql)

    def test_batch_uses_bounded_keyset_and_atomic_checkpoint_with_digest_checks(self):
        sql = render.render(options(), fixture())
        for expected in ("ORDER BY id LIMIT 2", "v_bytes > 100", "START TRANSACTION", "COMMIT;",
                         "BINARY SHA2(b.content, 256)", "BINARY b.name <=> BINARY e.source_name",
                         "b.expire_after_seconds IS NOT NULL", "WHERE NOT (BINARY e.disposition",
                         "FOR UPDATE", "GET_LOCK", "verified_retained_bytes", "ROLLBACK;"):
            self.assertIn(expected, sql)
        self.assertNotIn("RENAME TABLE", sql)
        self.assertNotIn("DELETE FROM", sql)

    def test_swap_has_unset_separate_approval_gate_and_keeps_source_table(self):
        sql = render.render(options(phase="swap"), fixture())
        self.assertIn("BINARY @mojito_mblob_cutover_approval", sql)
        self.assertIn("RENAME TABLE mblob TO `mblob_before_review`, `mblob_replacement_review` TO mblob", sql)
        self.assertNotIn("\nSET @mojito_mblob_cutover_approval", sql)
        self.assertNotIn("DROP TABLE", sql)
        self.assertIn("AUTO_INCREMENT", sql)

    def test_every_phase_blocks_canonical_content_or_retention_conflicts(self):
        for phase in ("prepare", "batch", "swap"):
            sql = render.render(options(phase=phase), fixture())
            self.assertIn("'RETAIN_CANONICAL_CONFLICT', 'RETAIN_RETENTION_CONFLICT'", sql)
            self.assertIn("disposition = 'CANONICAL_VERIFIED' AND reconciled = b'0'", sql)

    def test_expired_near_expiry_and_long_lived_proof_refused(self):
        now = dt.datetime.now(dt.timezone.utc)
        for seconds in (-1, 0, 20, 35, 306):
            proof = fixture(now)
            proof["validUntil"] = (now + dt.timedelta(seconds=seconds)).isoformat()
            with self.assertRaises(ValueError):
                render.render(options(), proof, now)

    def test_historical_status_incomplete_coverage_and_active_lease_refused(self):
        for field, value in (("status", "READY"), ("phase", "RECONCILE"), ("leaseToken", "active"),
                             ("lastError", "failure"), ("reconciliationCursor", 99),
                             ("canonicalCount", 2), ("retainedCount", -1)):
            proof = fixture()
            proof["evidence"][field] = value
            with self.assertRaises(ValueError):
                render.render(options(), proof)

    def test_identity_and_identifier_injection_refused(self):
        for changes in ({"database": "test; DROP DATABASE x"}, {"replacement": "mblob"},
                        {"backup": "mblob_before_'"}, {"max_rows": 1001}, {"max_bytes": 0}):
            with self.assertRaises(ValueError):
                render.render(options(**changes), fixture())
        for field, value in (("id", "bad'"), ("manifestSha256", "a'"),
                             ("destinationRoot", "https://host/path?sas=secret"),
                             ("fenceId", "bad'")):
            proof = fixture()
            proof["evidence"][field] = value
            with self.assertRaises(ValueError):
                render.render(options(), proof)

    def test_numeric_boolean_counts_and_naive_timestamps_refused(self):
        proof = fixture()
        proof["evidence"]["retainedCount"] = True
        with self.assertRaises(ValueError):
            render.render(options(), proof)

    def test_real_http_mapper_milliseconds_and_explicit_iso_are_equivalent(self):
        now = dt.datetime.now(dt.timezone.utc)
        proof = fixture(now)
        actual = render.validate_readiness(proof, now)
        proof["validUntil"] = render.timestamp(proof["validUntil"]).isoformat()
        proof["evidence"]["reconciledAt"] = render.timestamp(proof["evidence"]["reconciledAt"]).isoformat()
        self.assertEqual(actual[1], render.validate_readiness(proof, now)[1])
        for invalid in (True, 123.5, -1):
            with self.assertRaises(ValueError):
                render.timestamp(invalid)
        proof = fixture()
        proof["validUntil"] = "2026-09-14T12:00:00"
        with self.assertRaises(ValueError):
            render.render(options(), proof)

    def test_duplicate_json_keys_refused(self):
        with self.assertRaises(ValueError):
            json.loads('{"validUntil":1,"validUntil":2}', object_pairs_hook=render.unique_object)


if __name__ == "__main__":
    unittest.main()
