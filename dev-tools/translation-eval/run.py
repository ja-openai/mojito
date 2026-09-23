#!/usr/bin/env python3
"""Small, read-only translation experiment; Python standard library only."""

import argparse
import concurrent.futures
import copy
import hashlib
import html
import json
import os
from pathlib import Path
import random
import re
import statistics
import subprocess
import textwrap
import time
import urllib.error
import urllib.request

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
PROMPT_SOURCE = ROOT / "webapp/src/main/java/com/box/l10n/mojito/service/oaitranslate/AiTranslateType.java"
SCHEMA = {
    "type": "object", "additionalProperties": False, "required": ["targets"],
    "properties": {"targets": {"type": "array", "items": {
        "type": "object", "additionalProperties": False,
        "required": ["tmTextUnitId", "target"],
        "properties": {"tmTextUnitId": {"type": "integer"}, "target": {"type": "string"}},
    }}},
}


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def digest(value):
    return hashlib.sha256(encoded(value)).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def make_plan(args):
    dataset = json.loads(args.cases.read_text())
    cases = dataset["cases"]
    ids = [c["input"]["tmTextUnitId"] for c in cases]
    if len(ids) != len(set(ids)) or not cases:
        raise ValueError("Dataset must have nonempty, globally unique integer text-unit IDs")
    prompt_match = re.search(r'TARGET_ONLY_NEW\(\s*"""\n(.*?)""",', PROMPT_SOURCE.read_text(), re.S)
    if not prompt_match:
        raise ValueError("Cannot extract current TARGET_ONLY_NEW prompt; inspect Java source")
    # Current Java text block has only literal text, no Java escape sequences.
    prompt = textwrap.dedent(prompt_match.group(1))
    if "\\" in prompt:
        raise ValueError("Java escapes added: use a compiled prompt exporter before proceeding")
    groups = {}
    for case in cases:
        allowed = {"tmTextUnitId", "source", "sourceDescription", "glossaryTerms", "relatedStrings"}
        if set(case["input"]) != allowed or case["input"]["relatedStrings"]:
            raise ValueError("Pilot accepts only source, description, glossary, and empty relatedStrings; no target leakage")
        for check in case["checks"]:
            if check["kind"] not in ("contains", "regex", "not_regex"):
                raise ValueError("Unsupported check")
            if check["kind"] != "contains":
                re.compile(check["value"])
        groups.setdefault(case["locale"], []).append(case)
    jobs = []
    for arm in ("source_only", "context", "context_style"):
        for locale, group in sorted(groups.items()):
            if arm == "context_style" and locale not in dataset.get("localeSuffixes", {}):
                continue
            inputs = [copy.deepcopy(c["input"]) for c in group]
            if arm == "source_only":
                for item in inputs:
                    item["sourceDescription"] = None
                    item["glossaryTerms"] = []
            instructions = prompt + ("\n\n" + dataset["localeSuffixes"][locale] if arm == "context_style" else "")
            payload = {
                "model": args.model, "instructions": instructions,
                "input": [{"role": "user", "content": [{"type": "input_text", "text":
                    json.dumps({"locale": locale, "textUnitsToTranslate": inputs}, ensure_ascii=False)}]}],
                "reasoning": {"effort": args.effort},
                "text": {"verbosity": "low", "format": {"type": "json_schema", "name": "translations", "strict": True, "schema": SCHEMA}},
                "max_output_tokens": args.max_output_tokens, "store": False, "service_tier": "default",
            }
            for repetition in range(1, args.repetitions + 1):
                jobs.append({"id": f"{arm}-{locale}-{repetition}", "arm": arm, "locale": locale,
                             "repetition": repetition, "case_ids": [c["input"]["tmTextUnitId"] for c in group],
                             "request_hash": digest(payload), "request": payload})
    random.Random(20260918).shuffle(jobs)
    return {"version": 1, "dataset": dataset, "dataset_hash": digest(dataset),
            "git_revision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
            "prompt_source": str(PROMPT_SOURCE.relative_to(ROOT)), "prompt_hash": digest(prompt),
            "model": args.model, "effort": args.effort, "jobs": jobs}


def execute(job, output):
    path = output / (job["id"] + ".json")
    if path.exists():
        return job["id"] + " cached (including failures)"
    started = time.monotonic()
    result = {"job_id": job["id"], "request_hash": job["request_hash"], "attempts": []}
    for attempt in range(3):
        request = urllib.request.Request(
            "https://api.openai.com/v1/responses", data=encoded(job["request"]),
            headers={"Authorization": "Bearer " + os.environ["OPENAI_API_KEY"], "Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                result["response"] = json.load(response)
            result["attempts"].append({"http_status": 200})
            break
        except urllib.error.HTTPError as error:
            # Do not persist provider error messages: they can contain account identifiers.
            result["attempts"].append({"http_status": error.code})
            result["error"] = f"HTTP {error.code}"
            if error.code not in (429, 500, 502, 503, 504) or attempt == 2:
                break
            time.sleep(2 ** (attempt + 1))
        except (OSError, ValueError) as error:
            result["error"] = type(error).__name__
            break
    if "response" in result:
        result.pop("error", None)
    result["elapsed_seconds"] = round(time.monotonic() - started, 3)
    save(path, result)
    return job["id"] + " " + result.get("error", result.get("response", {}).get("status", "unknown"))


def read_targets(result, expected):
    response = result.get("response", {})
    if response.get("status") != "completed":
        return {}, result.get("error", "response not completed")
    try:
        text = "".join(c["text"] for o in response["output"] if o.get("type") == "message"
                       for c in o.get("content", []) if c.get("type") == "output_text")
        targets = json.loads(text)["targets"]
        ids = [t["tmTextUnitId"] for t in targets]
        if len(ids) != len(set(ids)) or set(ids) != set(expected):
            return {}, "missing, duplicate, or unexpected output IDs"
        if any(not isinstance(t["target"], str) or not t["target"].strip() for t in targets):
            return {}, "empty or invalid target"
        return {t["tmTextUnitId"]: t["target"] for t in targets}, None
    except (ValueError, KeyError, TypeError):
        return {}, "invalid output JSON"


def failures(case, target):
    failed = []
    for check in case["checks"]:
        kind, value = check["kind"], check["value"]
        matched = value in target if kind == "contains" else bool(re.search(value, target))
        if matched == (kind == "not_regex"):
            failed.append(check["reason"])
    return failed


def summarize(output):
    plan = json.loads((output / "plan.json").read_text())
    cases = {c["input"]["tmTextUnitId"]: c for c in plan["dataset"]["cases"]}
    rows, groups, job_stats = [], {}, []
    for job in plan["jobs"]:
        path = output / (job["id"] + ".json")
        result = json.loads(path.read_text()) if path.exists() else {"error": "not run"}
        if result.get("request_hash", job["request_hash"]) != job["request_hash"]:
            raise ValueError("Saved result/request hash mismatch")
        targets, error = read_targets(result, job["case_ids"])
        response = result.get("response", {})
        job_stats.append({"arm": job["arm"], "error": error, "elapsed_seconds": result.get("elapsed_seconds"),
                          "usage": response.get("usage", {}), "returned_model": response.get("model")})
        for case_id in job["case_ids"]:
            case, target = cases[case_id], targets.get(case_id, "")
            failed = [error] if error else failures(case, target)
            row = {"case_id": case_id, "locale": job["locale"], "arm": job["arm"],
                   "repetition": job["repetition"], "source": case["input"]["source"],
                   "target": target, "failures": failed, "passed": not failed}
            rows.append(row)
            key = job["arm"] + "/" + job["locale"]
            group = groups.setdefault(key, {"passed": 0, "total": 0, "execution_failures": 0})
            group["total"] += 1
            group["passed"] += not failed
            group["execution_failures"] += bool(error)
    summary = {"dataset_hash": plan["dataset_hash"], "model": plan["model"], "effort": plan["effort"],
               "groups": groups, "jobs": job_stats, "rows": rows}
    save(output / "summary.json", summary)
    lines = ["# Local translation probe", "", "Synthetic challenge cases; requirement checks, not a linguistic quality score.",
             "Repeats are not independent examples. No production translations were read or changed.", "",
             "Source-only deliberately omits context/glossary. Hindi/Bengali dot checks express a product policy",
             "supplied only in context_style; danda in the other arms is not inherently incorrect.", "",
             f"Requested model: `{plan['model']}`; reasoning: `{plan['effort']}`.", "",
             "| Arm / locale | Requirement passes | Execution failures |", "| --- | ---: | ---: |"]
    for key, value in sorted(groups.items()):
        lines.append(f"| {key} | {value['passed']}/{value['total']} | {value['execution_failures']} |")
    lines += ["", "All failed, missing, invalid, and incomplete outputs remain in the denominator."]
    for arm in sorted({j["arm"] for j in job_stats}):
        stats = [j for j in job_stats if j["arm"] == arm]
        times = [j["elapsed_seconds"] for j in stats if j["elapsed_seconds"] is not None]
        tokens = sum(j["usage"].get("total_tokens", 0) for j in stats)
        lines += [f"- {arm}: {len(stats)} requests; reported tokens {tokens}; "
                  f"median elapsed {statistics.median(times):.1f}s." if times else f"- {arm}: not run."]
    (output / "report.md").write_text("\n".join(lines) + "\n")
    e = html.escape
    cards = []
    for case_id, case in cases.items():
        comparisons = []
        for row in sorted((r for r in rows if r["case_id"] == case_id), key=lambda r: (r["repetition"], r["arm"])):
            comparisons.append(f"<tr><td>{e(row['arm'])} · {row['repetition']}</td><td>{e(row['target'])}</td>"
                               f"<td>{e('; '.join(row['failures']) or 'Checks pass')}</td></tr>")
        cards.append(f"<details><summary>{case_id} · {e(case['locale'])} · {e(case['input']['source'])}</summary>"
                     f"<p>{e(case['input']['sourceDescription'] or '')}</p><table>{''.join(comparisons)}</table></details>")
    (output / "report.html").write_text("<!doctype html><meta charset='utf-8'><title>Translation probe</title>"
        "<style>body{font:16px system-ui;max-width:1100px;margin:40px auto;padding:0 24px;color:#20242b}"
        "pre{white-space:pre-wrap}details{border-top:1px solid #ddd;padding:16px 0}summary{cursor:pointer;font-weight:600}"
        "table{width:100%;border-collapse:collapse}td{padding:12px;border-top:1px solid #eee;vertical-align:top;white-space:pre-wrap}"
        "td:first-child{width:180px}td:last-child{width:260px;color:#666}</style>"
        "<h1>Local translation probe</h1><pre>" + e("\n".join(lines[2:])) + "</pre>" + "".join(cards))
    print("\n".join(lines))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["plan", "run", "summarize"])
    parser.add_argument("--cases", type=Path, default=HERE / "cases.json")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--model", help="Explicit provider model ID, required for plan and run")
    parser.add_argument("--effort", default="max")
    parser.add_argument("--repetitions", type=int, default=2)
    parser.add_argument("--max-output-tokens", type=int, default=4096)
    args = parser.parse_args()
    if args.command == "summarize":
        summarize(args.out)
        return
    if not args.model or not args.model.strip():
        parser.error("--model is required for plan and run; choose a model available to your account")
    if not 1 <= args.repetitions <= 3 or not 128 <= args.max_output_tokens <= 8192:
        parser.error("Use 1–3 repetitions and 128–8192 output tokens per request")
    plan = make_plan(args)
    if len(plan["jobs"]) > 30:
        parser.error("Pilot is limited to 30 logical requests")
    args.out.mkdir(parents=True, exist_ok=True)
    plan_path = args.out / "plan.json"
    if plan_path.exists() and json.loads(plan_path.read_text()) != plan:
        parser.error("Output directory contains a different plan; choose a new directory")
    save(plan_path, plan)
    print(f"Frozen {len(plan['dataset']['cases'])} cases, {len(plan['jobs'])} requests; "
          f"output-token ceiling {len(plan['jobs']) * args.max_output_tokens} before bounded retries.", flush=True)
    if args.command == "plan":
        return
    if not os.environ.get("OPENAI_API_KEY"):
        parser.error("OPENAI_API_KEY must be set; never pass it as a CLI argument")
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        for future in concurrent.futures.as_completed([pool.submit(execute, j, args.out) for j in plan["jobs"]]):
            print(future.result(), flush=True)
    summarize(args.out)


if __name__ == "__main__":
    main()
