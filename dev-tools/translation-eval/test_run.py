"""Offline safety and accounting checks for the local experiment runner."""

import argparse
import contextlib
import io
import json
from pathlib import Path
import runpy
import tempfile
import types
import unittest
from unittest import mock


HERE = Path(__file__).resolve().parent
runner = types.SimpleNamespace(
    **runpy.run_path(str(HERE / "run.py"), run_name="translation_eval_tests")
)


def completed(targets):
    return {
        "response": {
            "status": "completed",
            "output": [
                {
                    "type": "message",
                    "content": [
                        {"type": "output_text", "text": json.dumps({"targets": targets})}
                    ],
                }
            ],
        }
    }


class TranslationEvalTest(unittest.TestCase):
    def test_plan_and_run_require_explicit_model_before_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "unused"
            for command in ("plan", "run"):
                with self.subTest(command=command):
                    with mock.patch("sys.argv", ["run.py", command, "--out", str(output)]):
                        with contextlib.redirect_stderr(io.StringIO()) as error:
                            with self.assertRaises(SystemExit) as raised:
                                runner.main()
                    self.assertEqual(raised.exception.code, 2)
                    self.assertIn("--model is required", error.getvalue())
                    self.assertFalse(output.exists())

    def test_null_description_can_be_planned_and_reported(self):
        dataset = json.loads((HERE / "cases.json").read_text())
        dataset["cases"][0]["input"]["sourceDescription"] = None
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            cases_path = output / "cases.json"
            runner.save(cases_path, dataset)
            plan = runner.make_plan(argparse.Namespace(
                cases=cases_path, model="offline", effort="medium", repetitions=1,
                max_output_tokens=512,
            ))
            runner.save(output / "plan.json", plan)
            with contextlib.redirect_stdout(io.StringIO()):
                runner.summarize(output)
            self.assertIn("<p></p><table>", (output / "report.html").read_text())
            self.assertTrue((output / "summary.json").is_file())

    def test_incomplete_response_cannot_pass_with_parseable_targets(self):
        result = completed([{"tmTextUnitId": 1, "target": "Bonjour"}])
        result["response"]["status"] = "incomplete"
        targets, error = runner.read_targets(result, [1])
        self.assertEqual(targets, {})
        self.assertIsNotNone(error)

    def test_missing_duplicate_and_unexpected_ids_fail_whole_request(self):
        valid = {"tmTextUnitId": 1, "target": "Bonjour"}
        for targets in ([], [valid, valid], [valid, {"tmTextUnitId": 2, "target": "Salut"}]):
            with self.subTest(targets=targets):
                parsed, error = runner.read_targets(completed(targets), [1])
                self.assertEqual(parsed, {})
                self.assertIsNotNone(error)

    def test_existing_target_is_refused_before_generation(self):
        dataset = json.loads((HERE / "cases.json").read_text())
        dataset["cases"][0]["input"]["existingTarget"] = {"content": "Held-out answer"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "cases.json"
            path.write_text(json.dumps(dataset))
            args = argparse.Namespace(
                cases=path, model="offline", effort="medium", repetitions=1, max_output_tokens=512
            )
            with self.assertRaisesRegex(ValueError, "no target leakage"):
                runner.make_plan(args)

    def test_missing_job_stays_in_denominator(self):
        cases = [
            {
                "locale": "fr",
                "input": {"tmTextUnitId": case_id, "source": "Hello", "sourceDescription": "Greeting"},
                "checks": [{"kind": "contains", "value": "Bonjour", "reason": "Greeting probe"}],
            }
            for case_id in (1, 2)
        ]
        jobs = [
            {
                "id": job_id,
                "arm": "context",
                "locale": "fr",
                "repetition": repetition,
                "case_ids": [1, 2],
                "request_hash": job_id,
            }
            for repetition, job_id in enumerate(("finished", "missing"), start=1)
        ]
        plan = {
            "dataset": {"cases": cases},
            "dataset_hash": "offline",
            "model": "offline",
            "effort": "medium",
            "jobs": jobs,
        }
        result = completed([{"tmTextUnitId": case_id, "target": "Bonjour"} for case_id in (1, 2)])
        result["request_hash"] = "finished"
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            runner.save(output / "plan.json", plan)
            runner.save(output / "finished.json", result)
            with contextlib.redirect_stdout(io.StringIO()):
                runner.summarize(output)
            summary = json.loads((output / "summary.json").read_text())
        self.assertEqual(
            summary["groups"]["context/fr"], {"passed": 2, "total": 4, "execution_failures": 2}
        )
        self.assertEqual(len(summary["rows"]), 4)
        self.assertEqual(sum(not row["passed"] for row in summary["rows"]), 2)


if __name__ == "__main__":
    unittest.main()
