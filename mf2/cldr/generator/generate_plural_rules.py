#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import pprint
import re
import shutil
import sys
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


CLDR_URLS = {
    "cardinal": "https://raw.githubusercontent.com/unicode-org/cldr-json/{ref}/cldr-json/cldr-core/supplemental/plurals.json",
    "ordinal": "https://raw.githubusercontent.com/unicode-org/cldr-json/{ref}/cldr-json/cldr-core/supplemental/ordinals.json",
}
PARENT_LOCALES_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/{ref}/"
    "cldr-json/cldr-core/supplemental/parentLocales.json"
)
CLDR_KEYS = {
    "cardinal": "plurals-type-cardinal",
    "ordinal": "plurals-type-ordinal",
}
PLURAL_CATEGORIES = ("zero", "one", "two", "few", "many", "other")
OPERANDS = ("n", "i", "v", "w", "f", "t", "e", "c")
OUTPUT_TARGETS = ("json", "python", "rust", "swift", "java", "kotlin", "javascript", "go", "php")
GENERATED_NOTICE = "Generated from Unicode CLDR by mf2/cldr/update_generated.sh; do not edit by hand."


@dataclass(frozen=True)
class Relation:
    operand: str
    modulo: int | None
    operator: str
    ranges: tuple[tuple[int, int], ...]

    def to_json(self) -> dict[str, Any]:
        data: dict[str, Any] = {
            "operand": self.operand,
            "operator": self.operator,
            "ranges": [list(value_range) for value_range in self.ranges],
        }
        if self.modulo is not None:
            data["modulo"] = self.modulo
        return data


Condition = tuple[tuple[Relation, ...], ...]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate CLDR plural rule implementations.")
    parser.add_argument("--locales", default="all", help="Comma-separated locales or 'all'.")
    parser.add_argument("--out", default="generated/all", help="Output directory.")
    parser.add_argument("--cldr-ref", default="main", help="unicode-org/cldr-json git ref.")
    parser.add_argument(
        "--targets",
        default="all",
        help="Comma-separated output targets: json,python,rust,swift,java,kotlin,javascript,go,php, or 'all'.",
    )
    parser.add_argument(
        "--java-package",
        default="com.box.l10n.mojito.mf2",
        help="Java package for generated Java sources.",
    )
    parser.add_argument(
        "--java-source-root",
        action="store_true",
        help="Write Java package directories directly under --out instead of --out/java.",
    )
    parser.add_argument(
        "--kotlin-package",
        default="com.box.l10n.mojito.mf2",
        help="Kotlin package for generated Kotlin sources.",
    )
    parser.add_argument(
        "--kotlin-source-root",
        action="store_true",
        help="Write Kotlin package directories directly under --out instead of --out/kotlin.",
    )
    parser.add_argument(
        "--javascript-source-root",
        action="store_true",
        help="Write JavaScript cldr_plural_rules.js directly under --out instead of --out/javascript.",
    )
    parser.add_argument(
        "--go-package",
        default="mf2",
        help="Go package name for generated Go sources.",
    )
    parser.add_argument(
        "--go-source-root",
        action="store_true",
        help="Write Go cldr_plural_rules.go directly under --out instead of --out/go.",
    )
    parser.add_argument(
        "--php-source-root",
        action="store_true",
        help="Write PHP CldrPluralRules.php directly under --out instead of --out/php.",
    )
    parser.add_argument(
        "--clean",
        action="store_true",
        help="Remove the output directory before writing generated files.",
    )
    parser.add_argument("--quiet", action="store_true", help="Do not print a generation summary.")
    parser.add_argument(
        "--types",
        default="cardinal,ordinal",
        help="Comma-separated plural types to generate: cardinal,ordinal.",
    )
    args = parser.parse_args(argv)

    plural_types = [item.strip() for item in args.types.split(",") if item.strip()]
    unknown_types = sorted(set(plural_types) - set(CLDR_URLS))
    if unknown_types:
        raise SystemExit(f"Unknown plural types: {', '.join(unknown_types)}")

    output_targets = select_output_targets(args.targets)

    raw_data = {
        plural_type: fetch_cldr_rules(plural_type, args.cldr_ref)
        for plural_type in plural_types
    }
    parent_locales = fetch_parent_locales(args.cldr_ref)
    selected_locales = select_locales(args.locales, raw_data)
    generated = build_generated_data(raw_data, parent_locales, selected_locales, args.cldr_ref)

    out = Path(args.out)
    if args.clean:
        shutil.rmtree(out, ignore_errors=True)

    output_paths = write_outputs(
        out,
        generated,
        output_targets,
        args.java_package,
        args.java_source_root,
        args.kotlin_package,
        args.kotlin_source_root,
        args.javascript_source_root,
        args.go_package,
        args.go_source_root,
        args.php_source_root,
    )
    if not args.quiet:
        size_summary = {
            target: path.stat().st_size
            for target, path in output_paths.items()
        }
        print(
            "Generated CLDR plural rules "
            f"locales={len(generated['locales'])} "
            f"cardinalRuleSets={len(generated.get('cardinal', {}).get('rules', []))} "
            f"ordinalRuleSets={len(generated.get('ordinal', {}).get('rules', []))} "
            f"targets={output_targets} "
            f"bytes={size_summary}"
        )
    return 0


def select_output_targets(target_arg: str) -> list[str]:
    if target_arg.strip().lower() == "all":
        return list(OUTPUT_TARGETS)
    selected = [item.strip().lower() for item in target_arg.split(",") if item.strip()]
    unknown = sorted(set(selected) - set(OUTPUT_TARGETS))
    if unknown:
        raise SystemExit(f"Unknown output targets: {', '.join(unknown)}")
    if not selected:
        raise SystemExit("At least one output target is required.")
    return list(dict.fromkeys(selected))


def write_outputs(
    out: Path,
    generated: dict[str, Any],
    output_targets: list[str],
    java_package: str,
    java_source_root: bool,
    kotlin_package: str,
    kotlin_source_root: bool,
    javascript_source_root: bool,
    go_package: str,
    go_source_root: bool,
    php_source_root: bool,
) -> dict[str, Path]:
    java_base = out if java_source_root else out / "java"
    kotlin_base = out if kotlin_source_root else out / "kotlin"
    javascript_path = (
        out / "cldr_plural_rules.js"
        if javascript_source_root
        else out / "javascript" / "cldr_plural_rules.js"
    )
    go_path = out / "cldr_plural_rules.go" if go_source_root else out / "go" / "cldr_plural_rules.go"
    php_path = out / "CldrPluralRules.php" if php_source_root else out / "php" / "CldrPluralRules.php"
    paths = {
        "json": out / "plural_rules.json",
        "python": out / "python" / "cldr_plural_rules.py",
        "rust": out / "rust" / "cldr_plural_rules.rs",
        "swift": out / "swift" / "CldrPluralRules.swift",
        "java": java_base / java_package_path(java_package) / "CldrPluralRules.java",
        "kotlin": kotlin_base / java_package_path(kotlin_package) / "CldrPluralRules.kt",
        "javascript": javascript_path,
        "go": go_path,
        "php": php_path,
    }
    writers = {
        "json": write_json,
        "python": write_python,
        "rust": write_rust,
        "swift": write_swift,
        "kotlin": lambda path, data: write_kotlin(path, data, kotlin_package),
        "javascript": write_javascript,
        "go": lambda path, data: write_go(path, data, go_package),
        "php": write_php,
    }
    for target in output_targets:
        if target == "java":
            write_java(paths[target], generated, java_package)
        else:
            writers[target](paths[target], generated)
    return {target: paths[target] for target in output_targets}


def java_package_path(java_package: str) -> Path:
    parts = java_package.split(".")
    if not parts or any(not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", part) for part in parts):
        raise SystemExit(f"Invalid Java package: {java_package}")
    return Path(*parts)


def fetch_cldr_rules(plural_type: str, cldr_ref: str) -> dict[str, dict[str, str]]:
    url = CLDR_URLS[plural_type].format(ref=cldr_ref)
    with urllib.request.urlopen(url, timeout=30) as response:
        data = json.loads(response.read())
    return data["supplemental"][CLDR_KEYS[plural_type]]


def fetch_parent_locales(cldr_ref: str) -> dict[str, str]:
    url = PARENT_LOCALES_URL.format(ref=cldr_ref)
    with urllib.request.urlopen(url, timeout=30) as response:
        data = json.loads(response.read())
    parent_locales = data["supplemental"]["parentLocales"]
    plural_parent_locales = parent_locales.get("plurals", {})
    return plural_parent_locales.get("parentLocale", {})


def select_locales(
    locale_arg: str, raw_data: dict[str, dict[str, dict[str, str]]]
) -> list[str]:
    all_locales = sorted({locale for rules in raw_data.values() for locale in rules})
    if locale_arg.strip().lower() == "all":
        return all_locales

    selected: list[str] = []
    for raw_locale in locale_arg.split(","):
        locale = normalize_locale(raw_locale)
        if not locale:
            continue
        resolved = resolve_locale(locale, all_locales)
        if resolved is None:
            raise SystemExit(f"Locale '{locale}' is not present in CLDR plural data.")
        if resolved not in selected:
            selected.append(resolved)
    return selected


def build_generated_data(
    raw_data: dict[str, dict[str, dict[str, str]]],
    parent_locales: dict[str, str],
    locales: list[str],
    cldr_ref: str,
) -> dict[str, Any]:
    result: dict[str, Any] = {
        "metadata": {
            "cldrRef": cldr_ref,
            "source": "unicode-org/cldr-json cldr-core supplemental plurals/ordinals",
            "generator": "mf2/cldr/generator/generate_plural_rules.py",
        },
        "locales": [canonical_locale_key(locale) for locale in locales],
    }

    for plural_type, rules_by_locale in raw_data.items():
        rule_ids: dict[str, str] = {}
        rule_sets: list[dict[str, Any]] = []
        locale_map: dict[str, str] = {}

        for locale in locales:
            rules = rules_by_locale.get(locale)
            if rules is None:
                rules = rules_by_locale.get(locale.split("_", 1)[0])
            if rules is None:
                continue
            parsed = parse_rule_set(rules)
            key = stable_json(parsed)
            rule_id = rule_ids.get(key)
            if rule_id is None:
                rule_id = f"r{len(rule_sets)}"
                rule_ids[key] = rule_id
                rule_sets.append({"id": rule_id, "categories": parsed})
            locale_map[canonical_locale_key(locale)] = rule_id

        result[plural_type] = {
            "locales": locale_map,
            "parents": build_plural_parent_map(parent_locales, locale_map),
            "rules": rule_sets,
        }

    return result


def build_plural_parent_map(
    parent_locales: dict[str, str], locale_map: dict[str, str]
) -> dict[str, str]:
    parents: dict[str, str] = {}
    for child, parent in parent_locales.items():
        child_key = canonical_locale_key(child)
        parent_key = canonical_locale_key(parent)
        if parent_key == "und":
            continue
        if child_key in locale_map:
            continue
        parent_rule = locale_map.get(parent_key)
        if parent_rule is None:
            continue
        structural_rule = first_structural_rule(child_key, locale_map)
        if structural_rule != parent_rule:
            parents[child_key] = parent_key
    return parents


def first_structural_rule(locale: str, locale_map: dict[str, str]) -> str | None:
    for candidate in locale_lookup_chain(locale)[1:]:
        rule_id = locale_map.get(candidate)
        if rule_id is not None:
            return rule_id
    return None


def parse_rule_set(rules: dict[str, str]) -> list[dict[str, Any]]:
    categories: list[dict[str, Any]] = []
    for category in PLURAL_CATEGORIES:
        raw_rule = rules.get(f"pluralRule-count-{category}")
        if raw_rule is None:
            continue
        condition = parse_condition(raw_rule)
        categories.append({"category": category, "condition": condition_to_json(condition)})
    if not any(item["category"] == "other" for item in categories):
        categories.append({"category": "other", "condition": None})
    return categories


def parse_condition(raw_rule: str) -> Condition | None:
    condition_text = raw_rule.split("@", 1)[0].strip()
    if not condition_text:
        return None

    or_groups: list[tuple[Relation, ...]] = []
    for or_part in re.split(r"\s+or\s+", condition_text):
        relations: list[Relation] = []
        for and_part in re.split(r"\s+and\s+", or_part.strip()):
            if and_part:
                relations.append(parse_relation(and_part))
        if relations:
            or_groups.append(tuple(relations))
    return tuple(or_groups)


def parse_relation(text: str) -> Relation:
    match = re.fullmatch(
        r"(?P<operand>[nivwftec])(?:\s*%\s*(?P<modulo>\d+))?\s*(?P<operator>=|!=)\s*(?P<ranges>.+)",
        text.strip(),
    )
    if not match:
        raise ValueError(f"Unsupported CLDR plural relation: {text!r}")
    ranges = []
    for item in match.group("ranges").split(","):
        item = item.strip()
        if ".." in item:
            start, end = item.split("..", 1)
            ranges.append((int(start), int(end)))
        else:
            value = int(item)
            ranges.append((value, value))
    return Relation(
        operand=match.group("operand"),
        modulo=int(match.group("modulo")) if match.group("modulo") else None,
        operator=match.group("operator"),
        ranges=tuple(ranges),
    )


def condition_to_json(condition: Condition | None) -> list[list[dict[str, Any]]] | None:
    if condition is None:
        return None
    return [[relation.to_json() for relation in and_group] for and_group in condition]


def normalize_locale(locale: str) -> str:
    return locale.strip().replace("-", "_")


def canonical_locale_key(locale: str) -> str:
    parts: list[str] = []
    for index, part in enumerate(locale.strip().replace("_", "-").split("-")):
        if not part:
            continue
        if len(part) == 1:
            break
        parts.append(canonical_subtag(index, part))
    return "-".join(parts)


def locale_lookup_chain(locale: str) -> list[str]:
    parts = [part for part in canonical_locale_key(locale).split("-") if part]
    return ["-".join(parts[:length]) for length in range(len(parts), 0, -1)]


def canonical_subtag(index: int, part: str) -> str:
    if index == 0:
        return part.lower()
    if len(part) == 4 and part.isalpha():
        return part.title()
    if (len(part) == 2 and part.isalpha()) or (len(part) == 3 and part.isdigit()):
        return part.upper()
    return part.lower()


def resolve_locale(locale: str, available: list[str]) -> str | None:
    candidates = [locale, locale.split("_", 1)[0]]
    for candidate in candidates:
        if candidate in available:
            return candidate
    return None


def stable_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def write_python(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = pprint.pformat(data, width=100, sort_dicts=True)
    source = f'''# {GENERATED_NOTICE}
from __future__ import annotations

from decimal import Decimal, InvalidOperation
from typing import Any

try:
    from ._locale_key import plural_lookup_chain
except ImportError:
    from _locale_key import plural_lookup_chain

DATA = {payload}
MAX_PLURAL_OPERAND_LENGTH = 256
MAX_PLURAL_OPERAND_DIGITS = 1000
MAX_SAFE_PLURAL_INTEGER = Decimal("9007199254740991")
MAX_SAFE_PLURAL_INTEGER_INT = 9007199254740991


class NumberOperands:
    def __init__(self, value: Any):
        raw = str(value).strip()
        if len(raw) > MAX_PLURAL_OPERAND_LENGTH:
            raise ValueError("Unsupported plural operand value")
        try:
            decimal = Decimal(raw).copy_abs()
        except InvalidOperation as exc:
            raise ValueError(f"Unsupported plural operand value: {{value!r}}") from exc
        if not decimal.is_finite():
            raise ValueError(f"Unsupported plural operand value: {{value!r}}")
        if abs(decimal.adjusted()) >= MAX_PLURAL_OPERAND_DIGITS:
            raise ValueError(f"Unsupported plural operand value: {{value!r}}")
        if decimal > MAX_SAFE_PLURAL_INTEGER:
            raise ValueError(f"Unsupported plural operand value: {{value!r}}")

        normalized = raw.lstrip("-+").lower()
        base = normalized.split("e", 1)[0]
        fraction = base.split(".", 1)[1] if "." in base else ""
        fraction_without_trailing_zero = fraction.rstrip("0")

        self.n = decimal
        self.i = int(decimal)
        self.v = len(fraction)
        self.w = len(fraction_without_trailing_zero)
        self.f = _parse_plural_digits(fraction)
        self.t = _parse_plural_digits(fraction_without_trailing_zero)
        self.e = 0
        self.c = 0

    def operand(self, name: str) -> Decimal | int:
        return getattr(self, name)


def _parse_plural_digits(value: str) -> int:
    if not value:
        return 0
    parsed = int(value)
    if parsed > MAX_SAFE_PLURAL_INTEGER_INT:
        raise ValueError("Unsupported plural operand value")
    return parsed


def select_cardinal(value: Any, locale: str) -> str:
    return select(value, locale, "cardinal")


def select_ordinal(value: Any, locale: str) -> str:
    return select(value, locale, "ordinal")


def select(value: Any, locale: str, plural_type: str = "cardinal") -> str:
    rules_for_type = DATA.get(plural_type)
    if rules_for_type is None:
        return "other"
    rule_id = _lookup_rule_id(
        rules_for_type["locales"],
        rules_for_type.get("parents", {{}}),
        locale,
    )
    if rule_id is None:
        return "other"
    rule_set = next(rule for rule in rules_for_type["rules"] if rule["id"] == rule_id)
    operands = NumberOperands(value)
    for item in rule_set["categories"]:
        condition = item["condition"]
        if condition is None or _matches_condition(operands, condition):
            return item["category"]
    return "other"


def _lookup_rule_id(locales: dict[str, str], parents: dict[str, str], locale: str) -> str | None:
    for candidate in plural_lookup_chain(locale, parents):
        rule_id = locales.get(candidate)
        if rule_id is not None:
            return rule_id
    return None


def _matches_condition(operands: NumberOperands, condition: list[list[dict[str, Any]]]) -> bool:
    return any(all(_matches_relation(operands, relation) for relation in and_group) for and_group in condition)


def _matches_relation(operands: NumberOperands, relation: dict[str, Any]) -> bool:
    value = operands.operand(relation["operand"])
    if relation.get("modulo") is not None:
        value = value % relation["modulo"]
    in_ranges = any(_matches_range(value, start, end) for start, end in relation["ranges"])
    return in_ranges if relation["operator"] == "=" else not in_ranges


def _matches_range(value: Decimal | int, start: int, end: int) -> bool:
    if isinstance(value, Decimal):
        if value != value.to_integral_value():
            return False
        value = int(value)
    return start <= value <= end
'''
    path.write_text(source, encoding="utf-8")


def write_rust(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    source = [
        f"// {GENERATED_NOTICE}",
        "use crate::locale_key::plural_lookup_chain;",
        "",
        "const MAX_PLURAL_OPERAND_LENGTH: usize = 256;",
        "const MAX_SAFE_PLURAL_INTEGER: f64 = 9_007_199_254_740_991.0;",
        "const MAX_SAFE_PLURAL_I64: i64 = 9_007_199_254_740_991;",
        "",
        "",
        "#[derive(Debug, Clone, Copy)]",
        "pub struct NumberOperands {",
        "    pub n: f64,",
        "    pub i: i64,",
        "    pub v: i64,",
        "    pub w: i64,",
        "    pub f: i64,",
        "    pub t: i64,",
        "    pub e: i64,",
        "    pub c: i64,",
        "}",
        "",
        "impl NumberOperands {",
        "    pub fn from_str(value: &str) -> Option<Self> {",
        "        if value.len() > MAX_PLURAL_OPERAND_LENGTH { return None; }",
        "        if !is_plural_decimal_operand(value) { return None; }",
        "        let n = value.parse::<f64>().ok()?.abs();",
        "        if !n.is_finite() || n > MAX_SAFE_PLURAL_INTEGER { return None; }",
        "        let normalized = value.trim_start_matches(['-', '+']).to_ascii_lowercase();",
        "        let base = normalized.split('e').next().unwrap_or(&normalized);",
        "        let fraction = base.split_once('.').map(|(_, fraction)| fraction).unwrap_or(\"\");",
        "        let fraction_trimmed = fraction.trim_end_matches('0');",
        "        Some(Self {",
        "            n,",
        "            i: n.trunc() as i64,",
        "            v: fraction.len() as i64,",
        "            w: fraction_trimmed.len() as i64,",
        "            f: parse_plural_i64(fraction)?,",
        "            t: parse_plural_i64(fraction_trimmed)?,",
        "            e: 0,",
        "            c: 0,",
        "        })",
        "    }",
        "",
        "    fn operand_i64(&self, name: &str) -> i64 {",
        "        match name {",
        "            \"i\" => self.i, \"v\" => self.v, \"w\" => self.w, \"f\" => self.f, \"t\" => self.t, \"e\" => self.e, \"c\" => self.c,",
        "            \"n\" => self.n as i64,",
        "            _ => 0,",
        "        }",
        "    }",
        "",
        "    fn operand_f64(&self, name: &str) -> f64 {",
        "        if name == \"n\" { self.n } else { self.operand_i64(name) as f64 }",
        "    }",
        "}",
        "",
        "fn parse_plural_i64(value: &str) -> Option<i64> {",
        "    if value.is_empty() { return Some(0); }",
        "    let digits = value.trim_start_matches('0');",
        "    let digits = if digits.is_empty() { \"0\" } else { digits };",
        "    let parsed = digits.parse::<i64>().ok()?;",
        "    if parsed > MAX_SAFE_PLURAL_I64 { return None; }",
        "    Some(parsed)",
        "}",
        "",
        "fn is_plural_decimal_operand(value: &str) -> bool {",
        "    let bytes = value.as_bytes();",
        "    let mut index = 0;",
        "    if bytes.is_empty() { return false; }",
        "    if matches!(bytes[index], b'+' | b'-') {",
        "        index += 1;",
        "        if index == bytes.len() { return false; }",
        "    }",
        "    if bytes[index] == b'0' {",
        "        index += 1;",
        "        if index < bytes.len() && bytes[index].is_ascii_digit() { return false; }",
        "    } else if bytes[index].is_ascii_digit() {",
        "        while index < bytes.len() && bytes[index].is_ascii_digit() { index += 1; }",
        "    } else {",
        "        return false;",
        "    }",
        "    if index < bytes.len() && bytes[index] == b'.' {",
        "        index += 1;",
        "        let start = index;",
        "        while index < bytes.len() && bytes[index].is_ascii_digit() { index += 1; }",
        "        if index == start { return false; }",
        "    }",
        "    if index < bytes.len() && matches!(bytes[index], b'e' | b'E') {",
        "        index += 1;",
        "        if index < bytes.len() && matches!(bytes[index], b'+' | b'-') { index += 1; }",
        "        let start = index;",
        "        while index < bytes.len() && bytes[index].is_ascii_digit() { index += 1; }",
        "        if index == start { return false; }",
        "    }",
        "    index == bytes.len()",
        "}",
        "",
        "pub fn select_cardinal(locale: &str, operands: NumberOperands) -> &'static str {",
        "    match lookup_rule_id(CARDINAL_LOCALES, CARDINAL_PARENTS, locale) {",
    ]
    for rule in data.get("cardinal", {}).get("rules", []):
        source.append(f'        Some("{rule["id"]}") => {rust_rule_function_name("cardinal", rule["id"])}(operands),')
    source.extend(["        _ => \"other\",", "    }", "}", ""])
    source.extend(
        [
            "pub fn select_ordinal(locale: &str, operands: NumberOperands) -> &'static str {",
            "    match lookup_rule_id(ORDINAL_LOCALES, ORDINAL_PARENTS, locale) {",
        ]
    )
    for rule in data.get("ordinal", {}).get("rules", []):
        source.append(f'        Some("{rule["id"]}") => {rust_rule_function_name("ordinal", rule["id"])}(operands),')
    source.extend(["        _ => \"other\",", "    }", "}", ""])
    source.append(render_rust_locale_map("CARDINAL_LOCALES", data.get("cardinal", {}).get("locales", {})))
    source.append(render_rust_locale_map("ORDINAL_LOCALES", data.get("ordinal", {}).get("locales", {})))
    source.append(render_rust_locale_map("CARDINAL_PARENTS", data.get("cardinal", {}).get("parents", {})))
    source.append(render_rust_locale_map("ORDINAL_PARENTS", data.get("ordinal", {}).get("parents", {})))
    source.extend(
        [
            "fn lookup_rule_id(",
            "    locales: &'static [(&'static str, &'static str)],",
            "    parents: &'static [(&'static str, &'static str)],",
            "    locale: &str,",
            ") -> Option<&'static str> {",
            "    plural_lookup_chain(locale, parents)",
            "        .into_iter()",
            "        .find_map(|lookup| locales.iter().find(|(candidate, _)| *candidate == lookup).map(|(_, rule)| *rule))",
            "}",
            "",
        ]
    )
    for plural_type in ("cardinal", "ordinal"):
        for rule in data.get(plural_type, {}).get("rules", []):
            source.append(render_rust_rule_function(plural_type, rule))
    path.write_text("\n".join(source).rstrip() + "\n", encoding="utf-8")


def render_rust_locale_map(name: str, locale_map: dict[str, str]) -> str:
    lines = [f"static {name}: &[(&str, &str)] = &["]
    for locale, rule_id in sorted(locale_map.items()):
        lines.append(f'    ("{locale}", "{rule_id}"),')
    lines.append("];\n")
    return "\n".join(lines)


def rust_rule_function_name(plural_type: str, rule_id: str) -> str:
    return f"select_{plural_type}_{rule_id}"


def render_rust_rule_function(plural_type: str, rule: dict[str, Any]) -> str:
    has_condition = any(item["condition"] is not None for item in rule["categories"])
    parameter = "operands" if has_condition else "_operands"
    lines = [f"fn {rust_rule_function_name(plural_type, rule['id'])}({parameter}: NumberOperands) -> &'static str {{"]
    for item in rule["categories"]:
        category = item["category"]
        condition = item["condition"]
        if condition is None:
            lines.append(f'    "{category}"')
            lines.append("}")
            lines.append("")
            return "\n".join(lines)
        else:
            lines.append(f'    if {render_rust_condition(condition)} {{ return "{category}"; }}')
    lines.append('    "other"')
    lines.append("}\n")
    return "\n".join(lines)


def render_rust_condition(condition: list[list[dict[str, Any]]]) -> str:
    groups = []
    for and_group in condition:
        groups.append(
            " && ".join(
                render_rust_relation(relation, wrap_or=len(and_group) > 1) for relation in and_group
            )
        )
    if len(groups) == 1:
        return groups[0]
    return " || ".join(f"({group})" if " && " in group else group for group in groups)


def render_rust_relation(relation: dict[str, Any], wrap_or: bool = False) -> str:
    if relation["operand"] == "n":
        value = f'operands.operand_f64("{relation["operand"]}")'
        if "modulo" in relation:
            value = f"({value} % {relation['modulo']}.0)"
        checks = [
            f"{value}.fract() == 0.0 && {start}.0 <= {value} && {value} <= {end}.0"
            for start, end in relation["ranges"]
        ]
    else:
        value = f'operands.operand_i64("{relation["operand"]}")'
        if "modulo" in relation:
            value = f"({value} % {relation['modulo']})"
        checks = [f"{start} <= {value} && {value} <= {end}" for start, end in relation["ranges"]]
    expression = checks[0] if len(checks) == 1 else " || ".join(f"({check})" for check in checks)
    if wrap_or and len(checks) > 1:
        expression = f"({expression})"
    return expression if relation["operator"] == "=" else f"!({expression})"


def write_swift(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    source = [
        f"// {GENERATED_NOTICE}",
        "import Foundation",
        "",
        "private let maxPluralOperandLength = 256",
        "private let maxSafePluralOperandInteger = 9_007_199_254_740_991.0",
        "private let maxSafePluralOperandInt: Int64 = 9_007_199_254_740_991",
        "private let pluralOperandPattern = #\"^[+-]?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?$\"#",
        "",
        "struct NumberOperands {",
        "    let n: Double",
        "    let i: Int64",
        "    let v: Int64",
        "    let w: Int64",
        "    let f: Int64",
        "    let t: Int64",
        "    let e: Int64",
        "    let c: Int64",
        "",
        "    init?(_ value: String) {",
        "        guard value.count <= maxPluralOperandLength else { return nil }",
        "        guard value.range(of: pluralOperandPattern, options: .regularExpression) != nil else { return nil }",
        "        guard let parsed = Double(value), parsed.isFinite else { return nil }",
        "        let n = abs(parsed)",
        "        guard n <= maxSafePluralOperandInteger else { return nil }",
        "        let normalized = value.trimmingCharacters(in: CharacterSet(charactersIn: \"-+\")).lowercased()",
        "        let base = normalized.split(separator: \"e\", maxSplits: 1).first.map(String.init) ?? normalized",
        "        let fraction = base.split(separator: \".\", maxSplits: 1).dropFirst().first.map(String.init) ?? \"\"",
        "        let fractionTrimmed = String(fraction.reversed().drop(while: { $0 == \"0\" }).reversed())",
        "        self.n = n",
        "        self.i = Int64(n.rounded(.towardZero))",
        "        self.v = Int64(fraction.count)",
        "        self.w = Int64(fractionTrimmed.count)",
        "        guard let f = parsePluralInt(fraction), let t = parsePluralInt(fractionTrimmed) else { return nil }",
        "        self.f = f",
        "        self.t = t",
        "        self.e = 0",
        "        self.c = 0",
        "    }",
        "",
        "    fileprivate func operand(_ name: String) -> Int64 {",
        "        switch name {",
        "        case \"i\": i",
        "        case \"v\": v",
        "        case \"w\": w",
        "        case \"f\": f",
        "        case \"t\": t",
        "        case \"e\": e",
        "        case \"c\": c",
        "        case \"n\": i",
        "        default: 0",
        "        }",
        "    }",
        "",
        "    fileprivate func operandDouble(_ name: String) -> Double {",
        "        if name == \"n\" { return n }",
        "        return Double(operand(name))",
        "    }",
        "}",
        "",
        "private func parsePluralInt(_ value: String) -> Int64? {",
        "    if value.isEmpty { return 0 }",
        "    let digits = String(value.drop(while: { $0 == \"0\" }))",
        "    let normalizedDigits = digits.isEmpty ? \"0\" : digits",
        "    guard let parsed = Int64(normalizedDigits), parsed <= maxSafePluralOperandInt else {",
        "        return nil",
        "    }",
        "    return parsed",
        "}",
        "",
        "func selectCardinal(locale: String, operands: NumberOperands) -> String {",
        "    switch lookupRuleId(locales: cardinalLocales, parents: cardinalParents, locale: locale) {",
    ]
    for rule in data.get("cardinal", {}).get("rules", []):
        source.append(f'    case "{rule["id"]}": {swift_rule_function_name("cardinal", rule["id"])}(operands)')
    source.extend(['    default: "other"', "    }", "}", ""])
    source.extend(
        [
            "func selectOrdinal(locale: String, operands: NumberOperands) -> String {",
            "    switch lookupRuleId(locales: ordinalLocales, parents: ordinalParents, locale: locale) {",
        ]
    )
    for rule in data.get("ordinal", {}).get("rules", []):
        source.append(f'    case "{rule["id"]}": {swift_rule_function_name("ordinal", rule["id"])}(operands)')
    source.extend(['    default: "other"', "    }", "}", ""])
    source.append(render_swift_locale_map("cardinalLocales", data.get("cardinal", {}).get("locales", {})))
    source.append(render_swift_locale_map("ordinalLocales", data.get("ordinal", {}).get("locales", {})))
    source.append(render_swift_locale_map("cardinalParents", data.get("cardinal", {}).get("parents", {})))
    source.append(render_swift_locale_map("ordinalParents", data.get("ordinal", {}).get("parents", {})))
    source.extend(
        [
            "private func lookupRuleId(locales: [String: String], parents: [String: String], locale: String) -> String? {",
            "    for candidate in MF2LocaleKey.pluralLookupChain(locale, parents: parents) {",
            "        if let rule = locales[candidate] { return rule }",
            "    }",
            "    return nil",
            "}",
            "",
        ]
    )
    for plural_type in ("cardinal", "ordinal"):
        for rule in data.get(plural_type, {}).get("rules", []):
            source.append(render_swift_rule_function(plural_type, rule))
    path.write_text("\n".join(source).rstrip() + "\n", encoding="utf-8")


def render_swift_locale_map(name: str, locale_map: dict[str, str]) -> str:
    if not locale_map:
        return f"private let {name}: [String: String] = [:]\n"
    lines = [f"private let {name}: [String: String] = ["]
    for locale, rule_id in sorted(locale_map.items()):
        lines.append(f'    "{locale}": "{rule_id}",')
    lines.append("]\n")
    return "\n".join(lines)


def swift_rule_function_name(plural_type: str, rule_id: str) -> str:
    return f"select{plural_type.title()}{rule_id.title()}"


def render_swift_rule_function(plural_type: str, rule: dict[str, Any]) -> str:
    has_condition = any(item["condition"] is not None for item in rule["categories"])
    parameter = "operands" if has_condition else "_"
    lines = [f"private func {swift_rule_function_name(plural_type, rule['id'])}(_ {parameter}: NumberOperands) -> String {{"]
    for item in rule["categories"]:
        category = item["category"]
        condition = item["condition"]
        if condition is None:
            lines.append(f'    return "{category}"')
            lines.append("}\n")
            return "\n".join(lines)
        else:
            lines.append(f'    if {render_swift_condition(condition)} {{ return "{category}" }}')
    lines.append('    return "other"')
    lines.append("}\n")
    return "\n".join(lines)


def render_swift_condition(condition: list[list[dict[str, Any]]]) -> str:
    return " || ".join(
        "(" + " && ".join(render_swift_relation(relation) for relation in and_group) + ")"
        for and_group in condition
    )


def render_swift_relation(relation: dict[str, Any]) -> str:
    if relation["operand"] == "n":
        value = f'operands.operandDouble("{relation["operand"]}")'
        if "modulo" in relation:
            value = f"({value}.truncatingRemainder(dividingBy: {relation['modulo']}.0))"
        checks = [
            f"({value}.rounded(.towardZero) == {value} && {start}.0 <= {value} && {value} <= {end}.0)"
            for start, end in relation["ranges"]
        ]
    else:
        value = f'operands.operand("{relation["operand"]}")'
        if "modulo" in relation:
            value = f"({value} % {relation['modulo']})"
        checks = [f"({start} <= {value} && {value} <= {end})" for start, end in relation["ranges"]]
    expression = "(" + " || ".join(checks) + ")"
    return expression if relation["operator"] == "=" else f"!{expression}"


def write_kotlin(path: Path, data: dict[str, Any], kotlin_package: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    source = [
        f"// {GENERATED_NOTICE}",
        f"package {kotlin_package}",
        "",
        "private const val MAX_PLURAL_OPERAND_LENGTH = 256",
        "private const val MAX_SAFE_PLURAL_INTEGER = 9_007_199_254_740_991.0",
        "private const val MAX_SAFE_PLURAL_LONG = 9_007_199_254_740_991L",
        "private val PLURAL_OPERAND_REGEX = Regex(\"^[+-]?(?:0|[1-9]\\\\d*)(?:\\\\.\\\\d+)?(?:[eE][+-]?\\\\d+)?$\")",
        "",
        "internal object CldrPluralRules {",
        "    fun selectCardinal(locale: String, operands: NumberOperands): String =",
        "        when (lookupRuleId(CARDINAL_LOCALES, CARDINAL_PARENTS, locale)) {",
    ]
    for rule in data.get("cardinal", {}).get("rules", []):
        source.append(
            f'            "{rule["id"]}" -> {kotlin_rule_function_name("cardinal", rule["id"])}(operands)'
        )
    source.extend(
        [
            '            else -> "other"',
            "        }",
            "",
            "    fun selectOrdinal(locale: String, operands: NumberOperands): String =",
            "        when (lookupRuleId(ORDINAL_LOCALES, ORDINAL_PARENTS, locale)) {",
        ]
    )
    for rule in data.get("ordinal", {}).get("rules", []):
        source.append(
            f'            "{rule["id"]}" -> {kotlin_rule_function_name("ordinal", rule["id"])}(operands)'
        )
    source.extend(
        [
            '            else -> "other"',
            "        }",
            "",
            render_kotlin_locale_map("CARDINAL_LOCALES", data.get("cardinal", {}).get("locales", {})),
            render_kotlin_locale_map("ORDINAL_LOCALES", data.get("ordinal", {}).get("locales", {})),
            render_kotlin_locale_map("CARDINAL_PARENTS", data.get("cardinal", {}).get("parents", {})),
            render_kotlin_locale_map("ORDINAL_PARENTS", data.get("ordinal", {}).get("parents", {})),
            "    private fun lookupRuleId(",
            "        locales: Map<String, String>,",
            "        parents: Map<String, String>,",
            "        locale: String,",
            "    ): String? {",
            "        for (candidate in LocaleKey.pluralLookupChain(locale, parents)) {",
            "            locales[candidate]?.let { return it }",
            "        }",
            "        return null",
            "    }",
            "}",
            "",
            "internal data class NumberOperands(",
            "    val n: Double,",
            "    val i: Long,",
            "    val v: Long,",
            "    val w: Long,",
            "    val f: Long,",
            "    val t: Long,",
            "    val e: Long = 0,",
            "    val c: Long = 0,",
            ") {",
            "    fun operandLong(name: String): Long =",
            "        when (name) {",
            '            "i" -> i',
            '            "v" -> v',
            '            "w" -> w',
            '            "f" -> f',
            '            "t" -> t',
            '            "e" -> e',
            '            "c" -> c',
            '            "n" -> n.toLong()',
            "            else -> 0",
            "        }",
            "",
            '    fun operandDouble(name: String): Double = if (name == "n") n else operandLong(name).toDouble()',
            "",
            "    companion object {",
            "        fun fromString(value: String): NumberOperands? {",
            "            val raw = value.trim()",
            "            if (raw.length > MAX_PLURAL_OPERAND_LENGTH) return null",
            "            if (!PLURAL_OPERAND_REGEX.matches(raw)) return null",
            "            val parsed = raw.toDoubleOrNull()?.let { kotlin.math.abs(it) } ?: return null",
            "            if (!parsed.isFinite() || parsed > MAX_SAFE_PLURAL_INTEGER) return null",
            '            val normalized = raw.trimStart(\'-\', \'+\').lowercase()',
            '            val base = normalized.substringBefore("e")',
            '            val fraction = base.substringAfter(".", "")',
            "            val trimmedFraction = fraction.trimEnd('0')",
            "            val f = parsePluralLong(fraction) ?: return null",
            "            val t = parsePluralLong(trimmedFraction) ?: return null",
            "            return NumberOperands(",
            "                n = parsed,",
            "                i = parsed.toLong(),",
            "                v = fraction.length.toLong(),",
            "                w = trimmedFraction.length.toLong(),",
            "                f = f,",
            "                t = t,",
            "            )",
            "        }",
            "    }",
            "}",
            "",
            "private fun parsePluralLong(value: String): Long? {",
            "    if (value.isEmpty()) return 0",
            "    val digits = value.trimStart('0').ifEmpty { \"0\" }",
            "    val parsed = digits.toLongOrNull() ?: return null",
            "    return parsed.takeIf { it <= MAX_SAFE_PLURAL_LONG }",
            "}",
            "",
        ]
    )
    for plural_type in ("cardinal", "ordinal"):
        for rule in data.get(plural_type, {}).get("rules", []):
            source.append(render_kotlin_rule_function(plural_type, rule))
    path.write_text("\n".join(source).rstrip() + "\n", encoding="utf-8")


def render_kotlin_locale_map(name: str, locale_map: dict[str, str]) -> str:
    if not locale_map:
        return f"    private val {name}: Map<String, String> = emptyMap()\n"
    lines = [f"    private val {name}: Map<String, String> = mapOf("]
    for locale, rule_id in sorted(locale_map.items()):
        lines.append(f'        "{locale}" to "{rule_id}",')
    lines.append("    )\n")
    return "\n".join(lines)


def kotlin_rule_function_name(plural_type: str, rule_id: str) -> str:
    return f"select{plural_type.title()}{rule_id.title()}"


def render_kotlin_rule_function(plural_type: str, rule: dict[str, Any]) -> str:
    has_condition = any(item["condition"] is not None for item in rule["categories"])
    parameter = "operands" if has_condition else "_operands"
    lines = [f"private fun {kotlin_rule_function_name(plural_type, rule['id'])}({parameter}: NumberOperands): String {{"]
    for item in rule["categories"]:
        category = item["category"]
        condition = item["condition"]
        if condition is None:
            lines.append(f'    return "{category}"')
            lines.append("}\n")
            return "\n".join(lines)
        lines.append(f'    if ({render_kotlin_condition(condition)}) return "{category}"')
    lines.append('    return "other"')
    lines.append("}\n")
    return "\n".join(lines)


def render_kotlin_condition(condition: list[list[dict[str, Any]]]) -> str:
    groups = []
    for and_group in condition:
        groups.append(
            " && ".join(
                render_kotlin_relation(relation, wrap_or=len(and_group) > 1)
                for relation in and_group
            )
        )
    if len(groups) == 1:
        return groups[0]
    return " || ".join(f"({group})" if " && " in group else group for group in groups)


def render_kotlin_relation(relation: dict[str, Any], wrap_or: bool = False) -> str:
    if relation["operand"] == "n":
        value = f'operands.operandDouble("{relation["operand"]}")'
        if "modulo" in relation:
            value = f"({value} % {relation['modulo']}.0)"
        checks = [
            f"{value} % 1.0 == 0.0 && {start}.0 <= {value} && {value} <= {end}.0"
            for start, end in relation["ranges"]
        ]
    else:
        value = f'operands.operandLong("{relation["operand"]}")'
        if "modulo" in relation:
            value = f"({value} % {relation['modulo']})"
        checks = [f"{start} <= {value} && {value} <= {end}" for start, end in relation["ranges"]]
    expression = checks[0] if len(checks) == 1 else " || ".join(f"({check})" for check in checks)
    if wrap_or and len(checks) > 1:
        expression = f"({expression})"
    return expression if relation["operator"] == "=" else f"!({expression})"


def write_java(path: Path, data: dict[str, Any], java_package: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    source = [
        f"// {GENERATED_NOTICE}",
        f"package {java_package};",
        "",
        "import java.util.Locale;",
        "import java.util.Map;",
        "import java.util.regex.Pattern;",
        "",
        "final class CldrPluralRules {",
        "    private CldrPluralRules() {}",
        "",
        "    static String selectCardinal(String locale, NumberOperands operands) {",
        "        return switch (lookupRuleId(CARDINAL_LOCALES, CARDINAL_PARENTS, locale)) {",
    ]
    for rule in data.get("cardinal", {}).get("rules", []):
        source.append(
            f'            case "{rule["id"]}" -> {java_rule_function_name("cardinal", rule["id"])}(operands);'
        )
    source.extend(
        [
            '            default -> "other";',
            "        };",
            "    }",
            "",
            "    static String selectOrdinal(String locale, NumberOperands operands) {",
            "        return switch (lookupRuleId(ORDINAL_LOCALES, ORDINAL_PARENTS, locale)) {",
        ]
    )
    for rule in data.get("ordinal", {}).get("rules", []):
        source.append(
            f'            case "{rule["id"]}" -> {java_rule_function_name("ordinal", rule["id"])}(operands);'
        )
    source.extend(
        [
            '            default -> "other";',
            "        };",
            "    }",
            "",
            render_java_locale_map("CARDINAL_LOCALES", data.get("cardinal", {}).get("locales", {})),
            render_java_locale_map("ORDINAL_LOCALES", data.get("ordinal", {}).get("locales", {})),
            render_java_locale_map("CARDINAL_PARENTS", data.get("cardinal", {}).get("parents", {})),
            render_java_locale_map("ORDINAL_PARENTS", data.get("ordinal", {}).get("parents", {})),
            "    private static String lookupRuleId(",
            "            Map<String, String> locales, Map<String, String> parents, String locale) {",
            "        for (String candidate : LocaleKey.pluralLookupChain(locale, parents)) {",
            "            String rule = locales.get(candidate);",
            "            if (rule != null) {",
            "                return rule;",
            "            }",
            "        }",
            "        return null;",
            "    }",
            "",
            "    private static boolean isInteger(double value) {",
            "        return Math.rint(value) == value;",
            "    }",
            "",
            "    static final class NumberOperands {",
            "        private static final int MAX_OPERAND_LENGTH = 256;",
            "        private static final double MAX_SAFE_PLURAL_INTEGER = 9_007_199_254_740_991.0;",
            "        private static final long MAX_SAFE_PLURAL_LONG = 9_007_199_254_740_991L;",
            "        private static final Pattern PLURAL_OPERAND_PATTERN =",
            "                Pattern.compile(\"^[+-]?(?:0|[1-9]\\\\d*)(?:\\\\.\\\\d+)?(?:[eE][+-]?\\\\d+)?$\");",
            "",
            "        private final double n;",
            "        private final long i;",
            "        private final long v;",
            "        private final long w;",
            "        private final long f;",
            "        private final long t;",
            "        private final long e;",
            "        private final long c;",
            "",
            "        private NumberOperands(double n, long i, long v, long w, long f, long t) {",
            "            this.n = n;",
            "            this.i = i;",
            "            this.v = v;",
            "            this.w = w;",
            "            this.f = f;",
            "            this.t = t;",
            "            this.e = 0;",
            "            this.c = 0;",
            "        }",
            "",
            "        static NumberOperands fromString(String raw) {",
            "            if (raw == null) {",
            "                return null;",
            "            }",
            "            String trimmed = raw.trim();",
            "            if (trimmed.length() > MAX_OPERAND_LENGTH) {",
            "                return null;",
            "            }",
            "            if (!PLURAL_OPERAND_PATTERN.matcher(trimmed).matches()) {",
            "                return null;",
            "            }",
            "            double parsed;",
            "            try {",
            "                parsed = Double.parseDouble(trimmed);",
            "            } catch (NumberFormatException error) {",
            "                return null;",
            "            }",
            "            if (!Double.isFinite(parsed)) {",
            "                return null;",
            "            }",
            "            double n = Math.abs(parsed);",
            "            if (n > MAX_SAFE_PLURAL_INTEGER) {",
            "                return null;",
            "            }",
            "            String normalized = trimmed;",
            "            while (normalized.startsWith(\"-\") || normalized.startsWith(\"+\")) {",
            "                normalized = normalized.substring(1);",
            "            }",
            "            normalized = normalized.toLowerCase(Locale.ROOT);",
            "            int exponentIndex = normalized.indexOf('e');",
            "            String base = exponentIndex >= 0 ? normalized.substring(0, exponentIndex) : normalized;",
            "            int dotIndex = base.indexOf('.');",
            "            String fraction = dotIndex >= 0 ? base.substring(dotIndex + 1) : \"\";",
            "            String trimmedFraction = trimTrailingZeros(fraction);",
            "            Long f = parseLong(fraction);",
            "            Long t = parseLong(trimmedFraction);",
            "            if (f == null || t == null) {",
            "                return null;",
            "            }",
            "            return new NumberOperands(",
            "                    n, (long) n, fraction.length(), trimmedFraction.length(), f, t);",
            "        }",
            "",
            "        private static Long parseLong(String value) {",
            "            if (value.isEmpty()) {",
            "                return 0L;",
            "            }",
            "            String digits = value.replaceFirst(\"^0+\", \"\");",
            "            if (digits.isEmpty()) {",
            "                digits = \"0\";",
            "            }",
            "            try {",
            "                long parsed = Long.parseLong(digits);",
            "                return parsed <= MAX_SAFE_PLURAL_LONG ? parsed : null;",
            "            } catch (NumberFormatException error) {",
            "                return null;",
            "            }",
            "        }",
            "",
            "        private static String trimTrailingZeros(String value) {",
            "            int end = value.length();",
            "            while (end > 0 && value.charAt(end - 1) == '0') {",
            "                end--;",
            "            }",
            "            return value.substring(0, end);",
            "        }",
            "",
            "        long operandI64(String name) {",
            "            return switch (name) {",
            '                case "i" -> i;',
            '                case "v" -> v;',
            '                case "w" -> w;',
            '                case "f" -> f;',
            '                case "t" -> t;',
            '                case "e" -> e;',
            '                case "c" -> c;',
            '                case "n" -> (long) n;',
            "                default -> 0;",
            "            };",
            "        }",
            "",
            "        double operandDouble(String name) {",
            "            return name.equals(\"n\") ? n : operandI64(name);",
            "        }",
            "    }",
            "",
        ]
    )
    for plural_type in ("cardinal", "ordinal"):
        for rule in data.get(plural_type, {}).get("rules", []):
            source.append(render_java_rule_function(plural_type, rule))
    source.append("}")
    path.write_text("\n".join(source).rstrip() + "\n", encoding="utf-8")


def write_javascript(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    source = [
        f"// {GENERATED_NOTICE}",
        "import { pluralLookupChain } from \"./locale-key.js\";",
        "",
        "const MAX_PLURAL_OPERAND_LENGTH = 256;",
        "const MAX_SAFE_PLURAL_INTEGER = Number.MAX_SAFE_INTEGER;",
        "",
        "export class NumberOperands {",
        "  constructor(value) {",
        "    if (typeof value === \"number\" && Number.isFinite(value) && Number.isInteger(value)) {",
        "      if (!Number.isSafeInteger(value)) throw new RangeError(`Unsupported plural operand value: ${value}`);",
        "      this.n = Math.abs(value);",
        "      this.i = Math.trunc(this.n);",
        "      this.v = 0;",
        "      this.w = 0;",
        "      this.f = 0;",
        "      this.t = 0;",
        "      this.e = 0;",
        "      this.c = 0;",
        "      return;",
        "    }",
        "    const raw = String(value).trim();",
        "    if (raw.length > MAX_PLURAL_OPERAND_LENGTH) {",
        "      throw new RangeError(\"Unsupported plural operand value\");",
        "    }",
        "    if (!/^[+-]?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?$/.test(raw)) {",
        "      throw new RangeError(`Unsupported plural operand value: ${value}`);",
        "    }",
        "    const n = Math.abs(Number(raw));",
        "    if (!Number.isFinite(n) || n > MAX_SAFE_PLURAL_INTEGER) throw new RangeError(`Unsupported plural operand value: ${value}`);",
        "    const normalized = raw.replace(/^[+-]+/, \"\").toLowerCase();",
        "    const base = normalized.split(\"e\", 1)[0];",
        "    const fraction = base.includes(\".\") ? base.split(\".\", 2)[1] : \"\";",
        "    const trimmedFraction = fraction.replace(/0+$/, \"\");",
        "    this.n = n;",
        "    this.i = Math.trunc(n);",
        "    this.v = fraction.length;",
        "    this.w = trimmedFraction.length;",
        "    this.f = parseSafePluralInteger(fraction);",
        "    this.t = parseSafePluralInteger(trimmedFraction);",
        "    this.e = 0;",
        "    this.c = 0;",
        "  }",
        "",
        "  operand(name) {",
        "    return this[name] ?? 0;",
        "  }",
        "}",
        "",
        "function parseSafePluralInteger(text) {",
        "  if (text === \"\") return 0;",
        "  const normalized = text.replace(/^0+/, \"\") || \"0\";",
        "  const value = Number(normalized);",
        "  if (!Number.isSafeInteger(value)) throw new RangeError(\"Unsupported plural operand value\");",
        "  return value;",
        "}",
        "",
        "export function selectCardinal(locale, value) {",
        "  const operands = value instanceof NumberOperands ? value : new NumberOperands(value);",
        "  switch (lookupRuleId(CARDINAL_LOCALES, CARDINAL_PARENTS, locale)) {",
    ]
    for rule in data.get("cardinal", {}).get("rules", []):
        source.append(f'    case "{rule["id"]}": return {javascript_rule_function_name("cardinal", rule["id"])}(operands);')
    source.extend(
        [
            '    default: return "other";',
            "  }",
            "}",
            "",
            "export function selectOrdinal(locale, value) {",
            "  const operands = value instanceof NumberOperands ? value : new NumberOperands(value);",
            "  switch (lookupRuleId(ORDINAL_LOCALES, ORDINAL_PARENTS, locale)) {",
        ]
    )
    for rule in data.get("ordinal", {}).get("rules", []):
        source.append(f'    case "{rule["id"]}": return {javascript_rule_function_name("ordinal", rule["id"])}(operands);')
    source.extend(
        [
            '    default: return "other";',
            "  }",
            "}",
            "",
            render_javascript_object("CARDINAL_LOCALES", data.get("cardinal", {}).get("locales", {})),
            render_javascript_object("ORDINAL_LOCALES", data.get("ordinal", {}).get("locales", {})),
            render_javascript_object("CARDINAL_PARENTS", data.get("cardinal", {}).get("parents", {})),
            render_javascript_object("ORDINAL_PARENTS", data.get("ordinal", {}).get("parents", {})),
            "function lookupRuleId(locales, parents, locale) {",
            "  for (const candidate of pluralLookupChain(locale, parents)) {",
            "    const rule = locales[candidate];",
            "    if (rule != null) return rule;",
            "  }",
            "  return null;",
            "}",
            "",
        ]
    )
    for plural_type in ("cardinal", "ordinal"):
        for rule in data.get(plural_type, {}).get("rules", []):
            source.append(render_javascript_rule_function(plural_type, rule))
    path.write_text("\n".join(source).rstrip() + "\n", encoding="utf-8")


def render_javascript_object(name: str, values: dict[str, str]) -> str:
    lines = [f"const {name} = {{"]
    for key, value in sorted(values.items()):
        lines.append(f'  "{key}": "{value}",')
    lines.append("};\n")
    return "\n".join(lines)


def javascript_rule_function_name(plural_type: str, rule_id: str) -> str:
    return f"select{plural_type.title()}{rule_id.title()}"


def render_javascript_rule_function(plural_type: str, rule: dict[str, Any]) -> str:
    has_condition = any(item["condition"] is not None for item in rule["categories"])
    parameter = "operands" if has_condition else "_operands"
    lines = [f"function {javascript_rule_function_name(plural_type, rule['id'])}({parameter}) {{"]
    for item in rule["categories"]:
        category = item["category"]
        condition = item["condition"]
        if condition is None:
            lines.append(f'  return "{category}";')
            lines.append("}\n")
            return "\n".join(lines)
        lines.append(f'  if ({render_javascript_condition(condition)}) return "{category}";')
    lines.append('  return "other";')
    lines.append("}\n")
    return "\n".join(lines)


def render_javascript_condition(condition: list[list[dict[str, Any]]]) -> str:
    groups = []
    for and_group in condition:
        groups.append(
            " && ".join(
                render_javascript_relation(relation, wrap_or=len(and_group) > 1)
                for relation in and_group
            )
        )
    if len(groups) == 1:
        return groups[0]
    return " || ".join(f"({group})" if " && " in group else group for group in groups)


def render_javascript_relation(relation: dict[str, Any], wrap_or: bool = False) -> str:
    value = f'operands.operand("{relation["operand"]}")'
    if "modulo" in relation:
        value = f"({value} % {relation['modulo']})"
    if relation["operand"] == "n":
        checks = [
            f"Number.isInteger({value}) && {start} <= {value} && {value} <= {end}"
            for start, end in relation["ranges"]
        ]
    else:
        checks = [
            f"{start} <= {value} && {value} <= {end}"
            for start, end in relation["ranges"]
        ]
    expression = checks[0] if len(checks) == 1 else " || ".join(f"({check})" for check in checks)
    if wrap_or and len(checks) > 1:
        expression = f"({expression})"
    return expression if relation["operator"] == "=" else f"!({expression})"


def write_php(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    source = [
        "<?php",
        "",
        "declare(strict_types=1);",
        "",
        "namespace Mojito\\MessageFormat2\\Internal;",
        "",
        f"// {GENERATED_NOTICE}",
        "",
        "function generated_select_cardinal(?string $locale, NumberOperands $operands): string",
        "{",
        "    return match (generated_lookup_rule_id(GENERATED_CARDINAL_LOCALES, GENERATED_CARDINAL_PARENTS, $locale)) {",
    ]
    for rule in data.get("cardinal", {}).get("rules", []):
        source.append(
            f"        {php_quote(rule['id'])} => {php_rule_function_name('cardinal', rule['id'])}($operands),"
        )
    source.extend(
        [
            "        default => 'other',",
            "    };",
            "}",
            "",
            "function generated_select_ordinal(?string $locale, NumberOperands $operands): string",
            "{",
            "    return match (generated_lookup_rule_id(GENERATED_ORDINAL_LOCALES, GENERATED_ORDINAL_PARENTS, $locale)) {",
        ]
    )
    for rule in data.get("ordinal", {}).get("rules", []):
        source.append(
            f"        {php_quote(rule['id'])} => {php_rule_function_name('ordinal', rule['id'])}($operands),"
        )
    source.extend(
        [
            "        default => 'other',",
            "    };",
            "}",
            "",
            render_php_array_constant("GENERATED_CARDINAL_LOCALES", data.get("cardinal", {}).get("locales", {})),
            render_php_array_constant("GENERATED_ORDINAL_LOCALES", data.get("ordinal", {}).get("locales", {})),
            render_php_array_constant("GENERATED_CARDINAL_PARENTS", data.get("cardinal", {}).get("parents", {})),
            render_php_array_constant("GENERATED_ORDINAL_PARENTS", data.get("ordinal", {}).get("parents", {})),
            "function generated_lookup_rule_id(array $locales, array $parents, ?string $locale): ?string",
            "{",
            "    foreach (plural_lookup_chain($locale, $parents) as $candidate) {",
            "        if (array_key_exists($candidate, $locales)) {",
            "            return $locales[$candidate];",
            "        }",
            "    }",
            "    return null;",
            "}",
            "",
        ]
    )
    for plural_type in ("cardinal", "ordinal"):
        for rule in data.get(plural_type, {}).get("rules", []):
            source.append(render_php_rule_function(plural_type, rule))
    path.write_text("\n".join(source).rstrip() + "\n", encoding="utf-8")


def render_php_array_constant(name: str, values: dict[str, str]) -> str:
    if not values:
        return f"const {name} = [];\n"
    lines = [f"const {name} = ["]
    for key, value in sorted(values.items()):
        lines.append(f"    {php_quote(key)} => {php_quote(value)},")
    lines.append("];\n")
    return "\n".join(lines)


def php_rule_function_name(plural_type: str, rule_id: str) -> str:
    return f"generated_select_{plural_type}_{rule_id}"


def render_php_rule_function(plural_type: str, rule: dict[str, Any]) -> str:
    has_condition = any(item["condition"] is not None for item in rule["categories"])
    parameter = "$operands" if has_condition else "$_operands"
    lines = [
        f"function {php_rule_function_name(plural_type, rule['id'])}(NumberOperands {parameter}): string",
        "{",
    ]
    for item in rule["categories"]:
        category = item["category"]
        condition = item["condition"]
        if condition is None:
            lines.append(f"    return {php_quote(category)};")
            lines.append("}\n")
            return "\n".join(lines)
        lines.append(f"    if ({render_php_condition(condition)}) {{ return {php_quote(category)}; }}")
    lines.append("    return 'other';")
    lines.append("}\n")
    return "\n".join(lines)


def render_php_condition(condition: list[list[dict[str, Any]]]) -> str:
    groups = []
    for and_group in condition:
        groups.append(
            " && ".join(
                render_php_relation(relation, wrap_or=len(and_group) > 1)
                for relation in and_group
            )
        )
    if len(groups) == 1:
        return groups[0]
    return " || ".join(f"({group})" if " && " in group else group for group in groups)


def render_php_relation(relation: dict[str, Any], wrap_or: bool = False) -> str:
    value = f"$operands->operand({php_quote(relation['operand'])})"
    if relation["operand"] == "n":
        if "modulo" in relation:
            value = f"fmod((float) {value}, {relation['modulo']}.0)"
        checks = [
            f"floor({value}) === {value} && {start}.0 <= {value} && {value} <= {end}.0"
            for start, end in relation["ranges"]
        ]
    else:
        if "modulo" in relation:
            value = f"({value} % {relation['modulo']})"
        checks = [f"{start} <= {value} && {value} <= {end}" for start, end in relation["ranges"]]
    expression = checks[0] if len(checks) == 1 else " || ".join(f"({check})" for check in checks)
    if wrap_or and len(checks) > 1:
        expression = f"({expression})"
    return expression if relation["operator"] == "=" else f"!({expression})"


def php_quote(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


def write_go(path: Path, data: dict[str, Any], go_package: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", go_package):
        raise SystemExit(f"Invalid Go package: {go_package}")
    source = [
        f"// {GENERATED_NOTICE}",
        f"package {go_package}",
        "",
        "import (",
        '    "math"',
        '    "regexp"',
        '    "strconv"',
        '    "strings"',
        ")",
        "",
        "func selectCardinal(locale string, value any) string {",
        "    operands, ok := newNumberOperands(value)",
        '    if !ok { return "" }',
        "    switch lookupRuleID(cardinalLocales, cardinalParents, locale) {",
    ]
    for rule in data.get("cardinal", {}).get("rules", []):
        source.append(f'    case "{rule["id"]}": return {go_rule_function_name("cardinal", rule["id"])}(operands)')
    source.extend(
        [
            '    default: return "other"',
            "    }",
            "}",
            "",
            "func selectOrdinal(locale string, value any) string {",
            "    operands, ok := newNumberOperands(value)",
            '    if !ok { return "" }',
            "    switch lookupRuleID(ordinalLocales, ordinalParents, locale) {",
        ]
    )
    for rule in data.get("ordinal", {}).get("rules", []):
        source.append(f'    case "{rule["id"]}": return {go_rule_function_name("ordinal", rule["id"])}(operands)')
    source.extend(
        [
            '    default: return "other"',
            "    }",
            "}",
            "",
            render_go_map("cardinalLocales", data.get("cardinal", {}).get("locales", {})),
            render_go_map("ordinalLocales", data.get("ordinal", {}).get("locales", {})),
            render_go_map("cardinalParents", data.get("cardinal", {}).get("parents", {})),
            render_go_map("ordinalParents", data.get("ordinal", {}).get("parents", {})),
            "func lookupRuleID(locales map[string]string, parents map[string]string, locale string) string {",
            "    for _, candidate := range pluralLookupChain(locale, parents) {",
            "        if rule := locales[candidate]; rule != \"\" { return rule }",
            "    }",
            "    return \"\"",
            "}",
            "",
            "type numberOperands struct {",
            "    n float64",
            "    i int64",
            "    v int64",
            "    w int64",
            "    f int64",
            "    t int64",
            "    e int64",
            "    c int64",
            "}",
            "",
            "const maxPluralOperandLength = 256",
            "const maxSafePluralInteger = 9007199254740991.0",
            "const maxSafePluralInt = 9007199254740991",
            "",
            "var pluralOperandRe = regexp.MustCompile(`^[+-]?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?$`)",
            "",
            "func newNumberOperands(value any) (numberOperands, bool) {",
            "    raw := strings.TrimSpace(valueToString(value))",
            "    if raw == \"\" || len(raw) > maxPluralOperandLength { return numberOperands{}, false }",
            "    if !pluralOperandRe.MatchString(raw) { return numberOperands{}, false }",
            "    parsed, err := strconv.ParseFloat(raw, 64)",
            "    if err != nil || math.IsInf(parsed, 0) || math.IsNaN(parsed) { return numberOperands{}, false }",
            "    n := math.Abs(parsed)",
            "    if n > maxSafePluralInteger { return numberOperands{}, false }",
            "    normalized := strings.ToLower(strings.TrimLeft(raw, \"+-\"))",
            "    base := strings.SplitN(normalized, \"e\", 2)[0]",
            "    fraction := \"\"",
            "    if dot := strings.Index(base, \".\"); dot >= 0 { fraction = base[dot+1:] }",
            "    trimmedFraction := strings.TrimRight(fraction, \"0\")",
            "    f, ok := parsePluralDigits(fraction)",
            "    if !ok { return numberOperands{}, false }",
            "    t, ok := parsePluralDigits(trimmedFraction)",
            "    if !ok { return numberOperands{}, false }",
            "    return numberOperands{",
            "        n: n,",
            "        i: int64(math.Trunc(n)),",
            "        v: int64(len(fraction)),",
            "        w: int64(len(trimmedFraction)),",
            "        f: f,",
            "        t: t,",
            "        e: 0,",
            "        c: 0,",
            "    }, true",
            "}",
            "",
            "func parsePluralDigits(value string) (int64, bool) {",
            "    if value == \"\" { return 0, true }",
            "    digits := strings.TrimLeft(value, \"0\")",
            "    if digits == \"\" { digits = \"0\" }",
            "    parsed, err := strconv.ParseInt(digits, 10, 64)",
            "    if err != nil || parsed > maxSafePluralInt { return 0, false }",
            "    return parsed, true",
            "}",
            "",
            "func (operands numberOperands) operand(name string) float64 {",
            "    switch name {",
            '    case "n": return operands.n',
            '    case "i": return float64(operands.i)',
            '    case "v": return float64(operands.v)',
            '    case "w": return float64(operands.w)',
            '    case "f": return float64(operands.f)',
            '    case "t": return float64(operands.t)',
            '    case "e": return float64(operands.e)',
            '    case "c": return float64(operands.c)',
            "    default: return 0",
            "    }",
            "}",
            "",
        ]
    )
    for plural_type in ("cardinal", "ordinal"):
        for rule in data.get(plural_type, {}).get("rules", []):
            source.append(render_go_rule_function(plural_type, rule))
    path.write_text("\n".join(source).rstrip() + "\n", encoding="utf-8")


def render_go_map(name: str, values: dict[str, str]) -> str:
    if not values:
        return f"var {name} = map[string]string{{}}\n"
    lines = [f"var {name} = map[string]string{{"]
    for key, value in sorted(values.items()):
        lines.append(f'    "{key}": "{value}",')
    lines.append("}\n")
    return "\n".join(lines)


def go_rule_function_name(plural_type: str, rule_id: str) -> str:
    return f"select{plural_type.title()}{rule_id.title()}"


def render_go_rule_function(plural_type: str, rule: dict[str, Any]) -> str:
    has_condition = any(item["condition"] is not None for item in rule["categories"])
    parameter = "operands" if has_condition else "_ numberOperands"
    if has_condition:
        header = f"func {go_rule_function_name(plural_type, rule['id'])}({parameter} numberOperands) string {{"
    else:
        header = f"func {go_rule_function_name(plural_type, rule['id'])}({parameter}) string {{"
    lines = [header]
    for item in rule["categories"]:
        category = item["category"]
        condition = item["condition"]
        if condition is None:
            lines.append(f'    return "{category}"')
            lines.append("}\n")
            return "\n".join(lines)
        lines.append(f'    if {render_go_condition(condition)} {{ return "{category}" }}')
    lines.append('    return "other"')
    lines.append("}\n")
    return "\n".join(lines)


def render_go_condition(condition: list[list[dict[str, Any]]]) -> str:
    groups = []
    for and_group in condition:
        groups.append(
            " && ".join(
                render_go_relation(relation, wrap_or=len(and_group) > 1)
                for relation in and_group
            )
        )
    if len(groups) == 1:
        return groups[0]
    return " || ".join(f"({group})" if " && " in group else group for group in groups)


def render_go_relation(relation: dict[str, Any], wrap_or: bool = False) -> str:
    value = f'operands.operand("{relation["operand"]}")'
    if "modulo" in relation:
        value = f"math.Mod({value}, {float(relation['modulo'])})"
    if relation["operand"] == "n":
        checks = [
            f"math.Trunc({value}) == {value} && {float(start)} <= {value} && {value} <= {float(end)}"
            for start, end in relation["ranges"]
        ]
    else:
        checks = [
            f"{float(start)} <= {value} && {value} <= {float(end)}"
            for start, end in relation["ranges"]
        ]
    expression = checks[0] if len(checks) == 1 else " || ".join(f"({check})" for check in checks)
    if wrap_or and len(checks) > 1:
        expression = f"({expression})"
    return expression if relation["operator"] == "=" else f"!({expression})"


def render_java_locale_map(name: str, locale_map: dict[str, str]) -> str:
    if not locale_map:
        return f"    private static final Map<String, String> {name} = Map.of();\n"
    lines = [f"    private static final Map<String, String> {name} = Map.ofEntries("]
    items = sorted(locale_map.items())
    for index, (locale, rule_id) in enumerate(items):
        suffix = "," if index < len(items) - 1 else ""
        lines.append(f'            Map.entry("{locale}", "{rule_id}"){suffix}')
    lines.append("    );\n")
    return "\n".join(lines)


def java_rule_function_name(plural_type: str, rule_id: str) -> str:
    return f"select{plural_type.title()}{rule_id.title()}"


def render_java_rule_function(plural_type: str, rule: dict[str, Any]) -> str:
    has_condition = any(item["condition"] is not None for item in rule["categories"])
    parameter = "operands" if has_condition else "ignored"
    lines = [
        f"    private static String {java_rule_function_name(plural_type, rule['id'])}"
        f"(NumberOperands {parameter}) {{"
    ]
    for item in rule["categories"]:
        category = item["category"]
        condition = item["condition"]
        if condition is None:
            lines.append(f'        return "{category}";')
            lines.append("    }\n")
            return "\n".join(lines)
        lines.append(f'        if ({render_java_condition(condition)}) {{ return "{category}"; }}')
    lines.append('        return "other";')
    lines.append("    }\n")
    return "\n".join(lines)


def render_java_condition(condition: list[list[dict[str, Any]]]) -> str:
    groups = []
    for and_group in condition:
        groups.append(
            " && ".join(
                render_java_relation(relation, wrap_or=len(and_group) > 1) for relation in and_group
            )
        )
    if len(groups) == 1:
        return groups[0]
    return " || ".join(f"({group})" if " && " in group else group for group in groups)


def render_java_relation(relation: dict[str, Any], wrap_or: bool = False) -> str:
    if relation["operand"] == "n":
        value = f'operands.operandDouble("{relation["operand"]}")'
        if "modulo" in relation:
            value = f"({value} % {relation['modulo']}.0)"
        checks = [
            f"isInteger({value}) && {start}.0 <= {value} && {value} <= {end}.0"
            for start, end in relation["ranges"]
        ]
    else:
        value = f'operands.operandI64("{relation["operand"]}")'
        if "modulo" in relation:
            value = f"({value} % {relation['modulo']})"
        checks = [f"{start} <= {value} && {value} <= {end}" for start, end in relation["ranges"]]
    expression = checks[0] if len(checks) == 1 else " || ".join(f"({check})" for check in checks)
    if wrap_or and len(checks) > 1:
        expression = f"({expression})"
    return expression if relation["operator"] == "=" else f"!({expression})"


if __name__ == "__main__":
    raise SystemExit(main())
