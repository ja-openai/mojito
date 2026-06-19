#!/usr/bin/env python3
"""Prototype a tiny French noun-gender classifier.

The experiment compiles this runtime shape:

    suffix rules
    + masculine-exception Bloom filter
    + feminine-exception Bloom filter
    + exact correction map

The supported-word contract is exact after build-time validation. Unknown words
remain best-effort guesses.
"""

from __future__ import annotations

import argparse
import csv
import html
import hashlib
import json
import math
import os
import tempfile
import urllib.request
import zipfile
from collections import Counter, defaultdict
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


LEXIQUE_URL = "http://www.lexique.org/databases/Lexique383/Lexique383.zip"


@dataclass(frozen=True)
class Noun:
    word: str
    gender: str
    frequency: float


@dataclass(frozen=True)
class SuffixRule:
    suffix: str
    gender: str
    confidence: float
    support: int


class BloomFilter:
    def __init__(self, items: list[str], false_positive_rate: float) -> None:
        self.count = len(items)
        if not items:
            self.bits = bytearray()
            self.bit_count = 0
            self.hash_count = 0
            return

        self.bit_count = max(
            8,
            math.ceil(-self.count * math.log(false_positive_rate) / (math.log(2) ** 2)),
        )
        self.hash_count = max(1, round((self.bit_count / self.count) * math.log(2)))
        self.bits = bytearray(math.ceil(self.bit_count / 8))
        for item in items:
            for bit in self._hashes(item):
                self.bits[bit >> 3] |= 1 << (bit & 7)

    def __contains__(self, item: str) -> bool:
        if not self.bits:
            return False
        return all(self.bits[bit >> 3] & (1 << (bit & 7)) for bit in self._hashes(item))

    def _hashes(self, item: str):
        digest = hashlib.blake2b(item.encode("utf-8"), digest_size=16).digest()
        h1 = int.from_bytes(digest[:8], "little")
        h2 = int.from_bytes(digest[8:], "little") or 1
        for i in range(self.hash_count):
            yield (h1 + i * h2) % self.bit_count

    @property
    def byte_size(self) -> int:
        return len(self.bits)


def ensure_lexique(zip_path: Path) -> Path:
    if zip_path.exists():
        return zip_path
    zip_path.parent.mkdir(parents=True, exist_ok=True)
    urllib.request.urlretrieve(LEXIQUE_URL, zip_path)
    return zip_path


def load_nouns(zip_path: Path) -> list[Noun]:
    by_word: dict[str, dict[str, float]] = defaultdict(lambda: {"m": 0.0, "f": 0.0})
    with zipfile.ZipFile(zip_path) as archive:
        with archive.open("Lexique383.tsv") as raw:
            rows = csv.DictReader((line.decode("utf-8") for line in raw), delimiter="\t")
            for row in rows:
                if row["cgram"] != "NOM" or row["genre"] not in {"m", "f"}:
                    continue
                word = normalize_word(row["ortho"])
                if not usable_word(word):
                    continue
                frequency = parse_float(row["freqlemfilms2"]) + parse_float(row["freqlemlivres"])
                by_word[word][row["genre"]] = max(by_word[word][row["genre"]], frequency)

    nouns: list[Noun] = []
    for word, genders in by_word.items():
        if genders["m"] and not genders["f"]:
            nouns.append(Noun(word, "m", genders["m"]))
        elif genders["f"] and not genders["m"]:
            nouns.append(Noun(word, "f", genders["f"]))
        else:
            # Homographs like "livre" have real masculine/feminine readings.
            # Leave them out of the exact single-gender dictionary.
            continue
    return sorted(nouns, key=lambda noun: (-noun.frequency, noun.word))


def normalize_word(word: str) -> str:
    return word.strip().lower().replace("’", "'")


def usable_word(word: str) -> bool:
    return len(word) > 1 and any(ch.isalpha() for ch in word) and " " not in word and "-" not in word


def parse_float(value: str) -> float:
    try:
        return float(value)
    except ValueError:
        return 0.0


def train_suffix_rules(
    nouns: list[Noun],
    max_suffix_len: int,
    min_support: int,
    min_confidence: float,
) -> list[SuffixRule]:
    counts: dict[str, Counter[str]] = defaultdict(Counter)
    for noun in nouns:
        max_len = min(max_suffix_len, len(noun.word))
        for size in range(1, max_len + 1):
            counts[noun.word[-size:]][noun.gender] += 1

    rules: list[SuffixRule] = []
    for suffix, counter in counts.items():
        support = counter["m"] + counter["f"]
        if support < min_support:
            continue
        gender, hits = counter.most_common(1)[0]
        confidence = hits / support
        if confidence >= min_confidence:
            rules.append(SuffixRule(suffix, gender, confidence, support))

    return sorted(rules, key=lambda rule: (-len(rule.suffix), -rule.confidence, -rule.support))


def suffix_guess(word: str, rules: list[SuffixRule]) -> str | None:
    for rule in rules:
        if word.endswith(rule.suffix):
            return rule.gender
    return None


def build_classifier(
    nouns: list[Noun],
    rules: list[SuffixRule],
    false_positive_rate: float,
) -> dict:
    exceptions = {"m": [], "f": []}
    for noun in nouns:
        guess = suffix_guess(noun.word, rules)
        if guess != noun.gender:
            exceptions[noun.gender].append(noun.word)

    blooms = {
        "m": BloomFilter(exceptions["m"], false_positive_rate),
        "f": BloomFilter(exceptions["f"], false_positive_rate),
    }

    corrections: dict[str, str] = {}
    for noun in nouns:
        guess = classify_without_correction(noun.word, rules, blooms)
        if guess != noun.gender:
            corrections[noun.word] = noun.gender

    return {"rules": rules, "exceptions": exceptions, "blooms": blooms, "corrections": corrections}


def classify_without_correction(
    word: str,
    rules: list[SuffixRule],
    blooms: dict[str, BloomFilter],
) -> str | None:
    m_hit = word in blooms["m"]
    f_hit = word in blooms["f"]
    if m_hit and not f_hit:
        return "m"
    if f_hit and not m_hit:
        return "f"
    if m_hit and f_hit:
        return None
    return suffix_guess(word, rules)


def classify(word: str, classifier: dict) -> str | None:
    normalized = normalize_word(word)
    correction = classifier["corrections"].get(normalized)
    if correction:
        return correction
    return classify_without_correction(normalized, classifier["rules"], classifier["blooms"])


def explain_classification(word: str, classifier: dict, known: dict[str, str]) -> dict:
    normalized = normalize_word(word)
    correction = classifier["corrections"].get(normalized)
    if correction:
        source = "correction"
        gender = correction
    else:
        m_hit = normalized in classifier["blooms"]["m"]
        f_hit = normalized in classifier["blooms"]["f"]
        if m_hit and not f_hit:
            source = "masculine-exception-bloom"
            gender = "m"
        elif f_hit and not m_hit:
            source = "feminine-exception-bloom"
            gender = "f"
        elif m_hit and f_hit:
            source = "ambiguous-bloom-hit"
            gender = None
        else:
            rule = matching_suffix_rule(normalized, classifier["rules"])
            source = f"suffix:{rule.suffix}" if rule else "unknown"
            gender = rule.gender if rule else None

    expected = known.get(normalized)
    return {
        "word": word,
        "normalized": normalized,
        "guess": gender_name(gender),
        "rawGuess": gender,
        "source": source,
        "validated": expected is not None and expected == gender,
        "expected": gender_name(expected) if expected else None,
        "supported": expected is not None,
    }


def matching_suffix_rule(word: str, rules: list[SuffixRule]) -> SuffixRule | None:
    for rule in rules:
        if word.endswith(rule.suffix):
            return rule
    return None


def gender_name(gender: str | None) -> str:
    if gender == "m":
        return "masculine"
    if gender == "f":
        return "feminine"
    return "unknown"


def estimate_sizes(classifier: dict) -> dict[str, int]:
    rules = classifier["rules"]
    corrections = classifier["corrections"]
    suffix_bytes = sum(len(rule.suffix.encode("utf-8")) + 4 for rule in rules)
    bloom_bytes = classifier["blooms"]["m"].byte_size + classifier["blooms"]["f"].byte_size
    correction_compact_bytes = sum(len(word.encode("utf-8")) + 1 for word in corrections)
    correction_hashmap_bytes = sum(len(word.encode("utf-8")) + 56 for word in corrections)
    return {
        "suffix_rules_bytes": suffix_bytes,
        "bloom_bytes": bloom_bytes,
        "correction_compact_bytes": correction_compact_bytes,
        "correction_hashmap_bytes": correction_hashmap_bytes,
        "compact_total_bytes": suffix_bytes + bloom_bytes + correction_compact_bytes,
        "hashmap_total_bytes": suffix_bytes + bloom_bytes + correction_hashmap_bytes,
    }


def profile(
    name: str,
    nouns: list[Noun],
    limit: int | None,
    min_support: int,
    min_confidence: float,
    false_positive_rate: float,
) -> dict:
    sample = nouns[:limit] if limit else nouns
    rules = train_suffix_rules(sample, max_suffix_len=8, min_support=min_support, min_confidence=min_confidence)
    classifier = build_classifier(sample, rules, false_positive_rate)
    correct = sum(classify(noun.word, classifier) == noun.gender for noun in sample)
    suffix_only_correct = sum(suffix_guess(noun.word, rules) == noun.gender for noun in sample)
    sizes = estimate_sizes(classifier)
    return {
        "name": name,
        "nouns": len(sample),
        "rules": len(rules),
        "suffix_only_accuracy": suffix_only_correct / len(sample),
        "validated_accuracy": correct / len(sample),
        "m_exceptions": len(classifier["exceptions"]["m"]),
        "f_exceptions": len(classifier["exceptions"]["f"]),
        "corrections": len(classifier["corrections"]),
        **sizes,
    }


def format_bytes(size: int) -> str:
    if size < 1024:
        return f"{size} B"
    if size < 1024 * 1024:
        return f"{size / 1024:.1f} KB"
    return f"{size / (1024 * 1024):.1f} MB"


def write_report(results: list[dict], output: Path) -> None:
    lines = [
        "# French Gender Experiment Results",
        "",
        "Generated from Lexique383 noun surface forms with unambiguous masculine/feminine gender.",
        "",
        "| Profile | Nouns | Rules | Suffix-only | Exact after corrections | Bloom exceptions | Corrections | Compact est. | HashMap est. |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for row in results:
        lines.append(
            "| {name} | {nouns} | {rules} | {suffix:.2%} | {exact:.2%} | {exceptions} | {corrections} | {compact} | {hashmap} |".format(
                name=row["name"],
                nouns=row["nouns"],
                rules=row["rules"],
                suffix=row["suffix_only_accuracy"],
                exact=row["validated_accuracy"],
                exceptions=row["m_exceptions"] + row["f_exceptions"],
                corrections=row["corrections"],
                compact=format_bytes(row["compact_total_bytes"]),
                hashmap=format_bytes(row["hashmap_total_bytes"]),
            )
        )
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


def serve_playground(classifier: dict, known: dict[str, str], host: str, port: int) -> None:
    class PlaygroundHandler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            parsed = urlparse(self.path)
            if parsed.path == "/api/guess":
                query = parse_qs(parsed.query)
                words = query.get("word", [""])[0].splitlines()
                payload = [explain_classification(word, classifier, known) for word in words if word.strip()]
                self._send_json(payload)
                return
            if parsed.path in {"/", "/index.html"}:
                self._send_html(playground_html())
                return
            self.send_error(404)

        def log_message(self, fmt: str, *args) -> None:
            print(f"{self.address_string()} - {fmt % args}")

        def _send_html(self, body: str) -> None:
            encoded = body.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

        def _send_json(self, payload) -> None:
            encoded = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

    server = ThreadingHTTPServer((host, port), PlaygroundHandler)
    print(f"French gender playground: http://{host}:{server.server_port}")
    server.serve_forever()


def playground_html() -> str:
    examples = html.escape("chien\nchienne\ntable\nlivre\narbre\nzorblade")
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>French Gender Playground</title>
  <style>
    :root {{
      color-scheme: light;
      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      color: #172033;
      background: #f6f7f9;
    }}
    body {{
      margin: 0;
      padding: 32px;
    }}
    main {{
      max-width: 980px;
      margin: 0 auto;
    }}
    h1 {{
      margin: 0 0 8px;
      font-size: 28px;
      line-height: 1.2;
    }}
    p {{
      margin: 0 0 20px;
      color: #536071;
    }}
    .layout {{
      display: grid;
      grid-template-columns: minmax(260px, 360px) 1fr;
      gap: 20px;
      align-items: start;
    }}
    textarea {{
      width: 100%;
      min-height: 240px;
      box-sizing: border-box;
      padding: 12px;
      border: 1px solid #c8ced8;
      border-radius: 8px;
      font: 15px/1.4 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      resize: vertical;
      background: white;
    }}
    button {{
      margin-top: 12px;
      padding: 9px 14px;
      border: 0;
      border-radius: 8px;
      background: #174ea6;
      color: white;
      font-weight: 650;
      cursor: pointer;
    }}
    table {{
      width: 100%;
      border-collapse: collapse;
      background: white;
      border: 1px solid #d8dde6;
      border-radius: 8px;
      overflow: hidden;
    }}
    th, td {{
      padding: 10px 12px;
      border-bottom: 1px solid #edf0f4;
      text-align: left;
      vertical-align: top;
      font-size: 14px;
    }}
    th {{
      background: #eef2f7;
      color: #334155;
      font-size: 12px;
      text-transform: uppercase;
      letter-spacing: .04em;
    }}
    tr:last-child td {{
      border-bottom: 0;
    }}
    .pill {{
      display: inline-block;
      padding: 2px 8px;
      border-radius: 999px;
      font-size: 12px;
      font-weight: 700;
      background: #eef2f7;
    }}
    .ok {{ color: #166534; background: #dcfce7; }}
    .guess {{ color: #92400e; background: #fef3c7; }}
    .unknown {{ color: #475569; background: #e2e8f0; }}
    @media (max-width: 760px) {{
      body {{ padding: 20px; }}
      .layout {{ grid-template-columns: 1fr; }}
    }}
  </style>
</head>
<body>
  <main>
    <h1>French Gender Playground</h1>
    <p>Try one word per line. Validated means the word is in the supported Lexique383 surface-form set.</p>
    <div class="layout">
      <section>
        <textarea id="words" spellcheck="false">{examples}</textarea>
        <button id="run">Guess gender</button>
      </section>
      <section>
        <table>
          <thead>
            <tr>
              <th>Word</th>
              <th>Guess</th>
              <th>Status</th>
              <th>Source</th>
            </tr>
          </thead>
          <tbody id="results"></tbody>
        </table>
      </section>
    </div>
  </main>
  <script>
    const words = document.querySelector("#words");
    const results = document.querySelector("#results");
    const run = document.querySelector("#run");

    function badge(text, cls) {{
      return `<span class="pill ${{cls}}">${{text}}</span>`;
    }}

    async function guess() {{
      const response = await fetch(`/api/guess?word=${{encodeURIComponent(words.value)}}`);
      const rows = await response.json();
      results.innerHTML = rows.map(row => {{
        const guessClass = row.rawGuess ? "guess" : "unknown";
        const status = row.validated
          ? badge("validated", "ok")
          : row.supported
            ? badge("mismatch", "unknown")
            : badge("best-effort", "guess");
        return `<tr>
          <td>${{row.word}}</td>
          <td>${{badge(row.guess, guessClass)}}</td>
          <td>${{status}}</td>
          <td>${{row.source}}</td>
        </tr>`;
      }}).join("");
    }}

    run.addEventListener("click", guess);
    words.addEventListener("keydown", event => {{
      if ((event.metaKey || event.ctrlKey) && event.key === "Enter") guess();
    }});
    guess();
  </script>
</body>
</html>
"""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--zip", type=Path, default=Path(tempfile.gettempdir()) / "Lexique383.zip")
    parser.add_argument("--output", type=Path, default=Path("dev-docs/experiments/french-gender/results.md"))
    parser.add_argument("--word", action="append", help="Classify one French noun. Can be repeated.")
    parser.add_argument("--serve", action="store_true", help="Start a local playground server.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()

    zip_path = ensure_lexique(args.zip)
    nouns = load_nouns(zip_path)
    if args.serve:
        rules = train_suffix_rules(nouns, max_suffix_len=8, min_support=20, min_confidence=0.88)
        classifier = build_classifier(nouns, rules, false_positive_rate=0.0005)
        known = {noun.word: noun.gender for noun in nouns}
        serve_playground(classifier, known, args.host, args.port)
        return

    if args.word:
        rules = train_suffix_rules(nouns, max_suffix_len=8, min_support=20, min_confidence=0.88)
        classifier = build_classifier(nouns, rules, false_positive_rate=0.0005)
        known = {noun.word: noun.gender for noun in nouns}
        for word in args.word:
            normalized = normalize_word(word)
            gender = classify(normalized, classifier)
            expected = known.get(normalized)
            exact = expected is not None and gender == expected
            print(
                f"{word}\tguess={gender or 'unknown'}\t"
                f"{'validated' if exact else 'best-effort'}"
                f"{f'\texpected={expected}' if expected else ''}"
            )
        return

    profiles = [
        ("tiny-embed", 2_000, 8, 0.92, 0.001),
        ("web-medium", 20_000, 12, 0.90, 0.001),
        ("backend-large", None, 20, 0.88, 0.0005),
    ]
    results = [profile(name, nouns, limit, support, confidence, fpr) for name, limit, support, confidence, fpr in profiles]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    write_report(results, args.output)
    print(args.output)


if __name__ == "__main__":
    main()
