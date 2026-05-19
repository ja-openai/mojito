#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import pprint
import re
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
    parser.add_argument("--locales", default="en,fr,ru,ar,ja", help="Comma-separated locales or 'all'.")
    parser.add_argument("--out", default="generated/minimal", help="Output directory.")
    parser.add_argument("--cldr-ref", default="main", help="unicode-org/cldr-json git ref.")
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

    raw_data = {
        plural_type: fetch_cldr_rules(plural_type, args.cldr_ref)
        for plural_type in plural_types
    }
    parent_locales = fetch_parent_locales(args.cldr_ref)
    selected_locales = select_locales(args.locales, raw_data)
    generated = build_generated_data(raw_data, parent_locales, selected_locales, args.cldr_ref)

    out = Path(args.out)
    write_json(out / "plural_rules.json", generated)
    write_python(out / "python" / "plural_rules.py", generated)
    write_rust(out / "rust" / "plural_rules.rs", generated)
    write_swift(out / "swift" / "PluralRules.swift", generated)

    size_summary = {
        "json": (out / "plural_rules.json").stat().st_size,
        "python": (out / "python" / "plural_rules.py").stat().st_size,
        "rust": (out / "rust" / "plural_rules.rs").stat().st_size,
        "swift": (out / "swift" / "PluralRules.swift").stat().st_size,
    }
    print(
        "Generated CLDR plural rules "
        f"locales={len(generated['locales'])} "
        f"cardinalRuleSets={len(generated.get('cardinal', {}).get('rules', []))} "
        f"ordinalRuleSets={len(generated.get('ordinal', {}).get('rules', []))} "
        f"bytes={size_summary}"
    )
    return 0


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
    source = f'''# Generated by mf2/cldr/generator/generate_plural_rules.py.
from __future__ import annotations

from decimal import Decimal, InvalidOperation
from typing import Any

DATA = {payload}


class NumberOperands:
    def __init__(self, value: Any):
        raw = str(value).strip()
        try:
            decimal = Decimal(raw).copy_abs()
        except InvalidOperation as exc:
            raise ValueError(f"Unsupported plural operand value: {{value!r}}") from exc
        if not decimal.is_finite():
            raise ValueError(f"Unsupported plural operand value: {{value!r}}")

        normalized = raw.lstrip("-+").lower()
        base = normalized.split("e", 1)[0]
        fraction = base.split(".", 1)[1] if "." in base else ""
        fraction_without_trailing_zero = fraction.rstrip("0")

        self.n = decimal
        self.i = int(decimal)
        self.v = len(fraction)
        self.w = len(fraction_without_trailing_zero)
        self.f = int(fraction) if fraction else 0
        self.t = int(fraction_without_trailing_zero) if fraction_without_trailing_zero else 0
        self.e = 0
        self.c = 0

    def operand(self, name: str) -> Decimal | int:
        return getattr(self, name)


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
    for candidate in _plural_lookup_chain(locale, parents):
        rule_id = locales.get(candidate)
        if rule_id is not None:
            return rule_id
    return None


def _plural_lookup_chain(locale: str, parents: dict[str, str]) -> list[str]:
    chain: list[str] = []
    _append_lookup_chain(_canonical_locale_tag(locale), parents, chain)
    return chain


def _append_lookup_chain(locale: str, parents: dict[str, str], chain: list[str]) -> None:
    current = locale
    while current:
        if current in chain:
            return
        chain.append(current)
        parent = parents.get(current)
        if parent is not None:
            _append_lookup_chain(parent, parents, chain)
        current = _structural_parent(current)


def _canonical_locale_tag(locale: str) -> str:
    parts = []
    for index, part in enumerate(locale.strip().replace("_", "-").split("-")):
        if not part:
            continue
        if len(part) == 1:
            break
        parts.append(_canonical_subtag(index, part))
    return "-".join(parts)


def _canonical_subtag(index: int, part: str) -> str:
    if index == 0:
        return part.lower()
    if len(part) == 4 and part.isalpha():
        return part.title()
    if (len(part) == 2 and part.isalpha()) or (len(part) == 3 and part.isdigit()):
        return part.upper()
    return part.lower()


def _structural_parent(locale: str) -> str:
    return locale.rsplit("-", 1)[0] if "-" in locale else ""


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
        "// Generated by mf2/cldr/generator/generate_plural_rules.py.",
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
        "        let n = value.parse::<f64>().ok()?.abs();",
        "        if !n.is_finite() { return None; }",
        "        let normalized = value.trim_start_matches(['-', '+']).to_ascii_lowercase();",
        "        let base = normalized.split('e').next().unwrap_or(&normalized);",
        "        let fraction = base.split_once('.').map(|(_, fraction)| fraction).unwrap_or(\"\");",
        "        let fraction_trimmed = fraction.trim_end_matches('0');",
        "        Some(Self {",
        "            n,",
        "            i: n.trunc() as i64,",
        "            v: fraction.len() as i64,",
        "            w: fraction_trimmed.len() as i64,",
        "            f: if fraction.is_empty() { 0 } else { fraction.parse().ok()? },",
        "            t: if fraction_trimmed.is_empty() { 0 } else { fraction_trimmed.parse().ok()? },",
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
            "fn plural_lookup_chain(locale: &str, parents: &'static [(&'static str, &'static str)]) -> Vec<String> {",
            "    let mut chain = Vec::new();",
            "    append_lookup_chain(&canonical_locale_tag(locale), parents, &mut chain);",
            "    chain",
            "}",
            "",
            "fn append_lookup_chain(locale: &str, parents: &'static [(&'static str, &'static str)], chain: &mut Vec<String>) {",
            "    let mut current = locale.to_string();",
            "    while !current.is_empty() {",
            "        if chain.iter().any(|candidate| candidate == &current) { return; }",
            "        chain.push(current.clone());",
            "        if let Some(parent) = parents.iter().find(|(child, _)| *child == current).map(|(_, parent)| *parent) {",
            "            append_lookup_chain(parent, parents, chain);",
            "        }",
            "        current = structural_parent(&current).unwrap_or_default();",
            "    }",
            "}",
            "",
            "fn canonical_locale_tag(locale: &str) -> String {",
            "    let normalized = locale.trim().replace('_', \"-\");",
            "    let mut parts = Vec::new();",
            "    for (index, part) in normalized.split('-').filter(|part| !part.is_empty()).enumerate() {",
            "        if part.len() == 1 { break; }",
            "        parts.push(canonical_subtag(index, part));",
            "    }",
            "    parts.join(\"-\")",
            "}",
            "",
            "fn canonical_subtag(index: usize, part: &str) -> String {",
            "    if index == 0 { return part.to_ascii_lowercase(); }",
            "    if part.len() == 4 && part.chars().all(|ch| ch.is_ascii_alphabetic()) {",
            "        let mut chars = part.chars();",
            "        let first = chars.next().map(|ch| ch.to_ascii_uppercase()).unwrap_or_default();",
            "        let rest = chars.as_str().to_ascii_lowercase();",
            "        return format!(\"{first}{rest}\");",
            "    }",
            "    if (part.len() == 2 && part.chars().all(|ch| ch.is_ascii_alphabetic()))",
            "        || (part.len() == 3 && part.chars().all(|ch| ch.is_ascii_digit()))",
            "    {",
            "        return part.to_ascii_uppercase();",
            "    }",
            "    part.to_ascii_lowercase()",
            "}",
            "",
            "fn structural_parent(locale: &str) -> Option<String> {",
            "    locale.rsplit_once('-').map(|(parent, _)| parent.to_string())",
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
        "// Generated by mf2/cldr/generator/generate_plural_rules.py.",
        "import Foundation",
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
        "        guard let parsed = Double(value), parsed.isFinite else { return nil }",
        "        let n = abs(parsed)",
        "        let normalized = value.trimmingCharacters(in: CharacterSet(charactersIn: \"-+\")).lowercased()",
        "        let base = normalized.split(separator: \"e\", maxSplits: 1).first.map(String.init) ?? normalized",
        "        let fraction = base.split(separator: \".\", maxSplits: 1).dropFirst().first.map(String.init) ?? \"\"",
        "        let fractionTrimmed = String(fraction.reversed().drop(while: { $0 == \"0\" }).reversed())",
        "        self.n = n",
        "        self.i = Int64(n.rounded(.towardZero))",
        "        self.v = Int64(fraction.count)",
        "        self.w = Int64(fractionTrimmed.count)",
        "        self.f = Int64(fraction) ?? 0",
        "        self.t = Int64(fractionTrimmed) ?? 0",
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
        "        case \"n\": Int64(n.rounded(.towardZero))",
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
            "    for candidate in pluralLookupChain(locale, parents: parents) {",
            "        if let rule = locales[candidate] { return rule }",
            "    }",
            "    return nil",
            "}",
            "",
            "private func pluralLookupChain(_ locale: String, parents: [String: String]) -> [String] {",
            "    var chain: [String] = []",
            "    appendLookupChain(canonicalLocaleTag(locale), parents: parents, chain: &chain)",
            "    return chain",
            "}",
            "",
            "private func appendLookupChain(_ locale: String, parents: [String: String], chain: inout [String]) {",
            "    var current = locale",
            "    while !current.isEmpty {",
            "        if chain.contains(current) { return }",
            "        chain.append(current)",
            "        if let parent = parents[current] {",
            "            appendLookupChain(parent, parents: parents, chain: &chain)",
            "        }",
            "        current = structuralParent(current) ?? \"\"",
            "    }",
            "}",
            "",
            "private func canonicalLocaleTag(_ locale: String) -> String {",
            "    let rawParts = locale.trimmingCharacters(in: .whitespacesAndNewlines)",
            "        .replacingOccurrences(of: \"_\", with: \"-\")",
            "        .split(separator: \"-\")",
            "        .map(String.init)",
            "    var parts: [String] = []",
            "    for (index, part) in rawParts.enumerated() {",
            "        if part.count == 1 { break }",
            "        parts.append(canonicalSubtag(index: index, part: part))",
            "    }",
            "    return parts.joined(separator: \"-\")",
            "}",
            "",
            "private func canonicalSubtag(index: Int, part: String) -> String {",
            "    if index == 0 { return part.lowercased() }",
            "    if part.count == 4, part.allSatisfy(\\.isLetter) {",
            "        return part.prefix(1).uppercased() + part.dropFirst().lowercased()",
            "    }",
            "    if (part.count == 2 && part.allSatisfy(\\.isLetter)) || (part.count == 3 && part.allSatisfy(\\.isNumber)) {",
            "        return part.uppercased()",
            "    }",
            "    return part.lowercased()",
            "}",
            "",
            "private func structuralParent(_ locale: String) -> String? {",
            "    guard let index = locale.lastIndex(of: \"-\") else { return nil }",
            "    return String(locale[..<index])",
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


if __name__ == "__main__":
    raise SystemExit(main())
