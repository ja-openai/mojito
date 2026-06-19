#!/usr/bin/env python3
"""Prototype resource-bundle loader for MF2 grammar-agreement fixtures.

This is deliberately a small MF2 subset runner, not a complete MF2
implementation. It loads the proposal fixtures, resolves term/person resources,
evaluates the proposed grammar functions against locale adapters, and includes
a tiny term-level MF2 matcher for explicit term form patterns.
"""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any


EXPRESSION_RE = re.compile(r"\{\$(?P<arg>[A-Za-z_][\w.-]*)(?:\s+:(?P<fn>[A-Za-z_][\w.-]*)(?P<opts>[^{}]*))?\}")
OPTION_RE = re.compile(r"(?P<key>[A-Za-z_][\w.-]*)=(?:\$(?P<ref>[A-Za-z_][\w.-]*)|(?P<value>[^\s]+))")
INPUT_RE = re.compile(r"\.input\s+\{\$(?P<name>[A-Za-z_][\w.-]*)\s+:[^}]+\}")
MATCH_RE = re.compile(r"\.match\s+(?P<selectors>(?:\$[A-Za-z_][\w.-]*\s*)+)")
PLACEHOLDER_RE = re.compile(r"\{\$(?P<name>[A-Za-z_][\w.-]*)\}")
VARIANT_RE = re.compile(r"(?P<keys>(?:\S+\s+)*?\S+)\s+\{\{(?P<body>.*?)\}\}")


@dataclass(frozen=True)
class BundleValue:
    id: str
    data: dict[str, Any]
    kind: str

    @property
    def text(self) -> str:
        if self.kind == "person":
            return self.data["displayName"]
        return self.data["text"]

    @property
    def morphology(self) -> dict[str, Any]:
        return self.data.get("morphology", {})

    @property
    def forms(self) -> dict[str, Any]:
        return self.data.get("forms", {})


class DiagnosticError(Exception):
    def __init__(self, code: str, **fields: str) -> None:
        super().__init__(code)
        self.code = code
        self.fields = fields

    def matches(self, expected: dict[str, str]) -> bool:
        if expected.get("code") != self.code:
            return False
        return all(str(self.fields.get(key)) == str(value) for key, value in expected.items() if key != "code")

    def to_dict(self) -> dict[str, str]:
        return {"code": self.code, **self.fields}


class ResourceStore:
    """Dumb key/value resource access.

    The formatter only needs named JSON-ish values. Android XML, web JSON,
    SQLite, binary packs, or remote configs can all implement this shape.
    """

    def get_json(self, key: str) -> Any:
        raise NotImplementedError


class NestedJsonStore(ResourceStore):
    def __init__(self, data: dict[str, Any]) -> None:
        self.data = data

    def get_json(self, key: str) -> Any:
        current: Any = self.data
        for part in key.split("."):
            current = current[part]
        return current


class GrammarBundle:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._init_from_data(json.loads(path.read_text(encoding="utf-8")))

    @classmethod
    def from_data(cls, data: dict[str, Any], label: str = "<memory>") -> "GrammarBundle":
        bundle = cls.__new__(cls)
        bundle.path = Path(label)
        bundle._init_from_data(data)
        return bundle

    def _init_from_data(self, data: dict[str, Any]) -> None:
        self.store = NestedJsonStore(data)
        self.locale = self.store.get_json("locale")
        self.profile = self.store.get_json("profile") if "profile" in self.store.data else self.locale
        self.messages = self.store.get_json("messages")
        self.terms = self.store.data.get("terms", {})
        self.people = self.store.data.get("people", {})
        self.adapter = adapter_for(self.locale)

    def resolve(self, value_id: str) -> BundleValue:
        if value_id in self.terms:
            return BundleValue(value_id, self.terms[value_id], "term")
        if value_id in self.people:
            return BundleValue(value_id, self.people[value_id], "person")
        raise KeyError(f"Unknown bundle value: {value_id}")

    def format(self, message_id: str, args: dict[str, str]) -> str:
        pattern = self.messages[message_id]["value"]

        def replace(match: re.Match[str]) -> str:
            arg_name = match.group("arg")
            fn_name = match.group("fn")
            opts = parse_options(match.group("opts") or "")
            arg_value = args[arg_name]
            value = self.runtime_value(arg_name, arg_value)
            if not fn_name:
                return value.text
            return self.adapter.call(fn_name, value, opts, args, self)

        return EXPRESSION_RE.sub(replace, pattern)

    def runtime_value(self, arg_name: str, arg_value: Any) -> BundleValue:
        if isinstance(arg_value, str) and arg_value.startswith("raw:"):
            return BundleValue(arg_name, {"text": arg_value.removeprefix("raw:")}, "literal")
        if isinstance(arg_value, str) and is_resource_id(arg_value):
            return self.resolve(arg_value)
        return BundleValue(arg_name, {"text": str(arg_value)}, "literal")

    def validate_examples(self) -> list[str]:
        failures: list[str] = []
        for message_id, message in self.messages.items():
            for index, example in enumerate(message.get("examples", []), start=1):
                if "error" in example:
                    try:
                        self.format(message_id, example["args"])
                    except DiagnosticError as exc:
                        if not exc.matches(example["error"]):
                            failures.append(
                                f"{self.path}:{message_id} example {index}: expected error {example['error']!r}, got {exc.to_dict()!r}"
                            )
                    else:
                        failures.append(
                            f"{self.path}:{message_id} example {index}: expected error {example['error']!r}, got success"
                        )
                    continue
                actual = self.format(message_id, example["args"])
                expected = example["output"]
                if actual != expected:
                    failures.append(
                        f"{self.path}:{message_id} example {index}: expected {expected!r}, got {actual!r}"
                    )
        return failures


def parse_options(options: str) -> dict[str, str]:
    return {
        match.group("key"): match.group("ref") or match.group("value")
        for match in OPTION_RE.finditer(options)
    }


def resolve_scalar_options(options: dict[str, str], args: dict[str, Any]) -> dict[str, str]:
    reference_options = {"with", "of"}
    resolved: dict[str, str] = {}
    for key, value in options.items():
        if key not in reference_options and value in args and not is_resource_id(args[value]):
            resolved[key] = str(args[value])
        else:
            resolved[key] = value
    return resolved


def option_context(options: dict[str, str], value: BundleValue | None = None) -> dict[str, Any]:
    usage = options.get("usage")
    if not usage:
        article = options.get("article") or options.get("definiteness")
        usage = article if article in {"definite", "indefinite", "partitive"} else "bare"
    return {
        "usage": usage,
        "case": options.get("case", "*"),
        "number": options.get("number") or (value.morphology.get("number") if value else "singular"),
        "count": options.get("count", "*"),
        "gender": value.morphology.get("gender", "*") if value else "*",
        "animacy": value.morphology.get("animacy", "*") if value else "*",
    }


def plural_key_matches(key: str, value: Any) -> bool:
    if key == "*":
        return True
    if key.isdigit():
        return str(value) == key
    try:
        count = float(value)
    except (TypeError, ValueError):
        return str(value) == key
    if key == "one":
        return count == 1
    if key == "other":
        return count != 1
    return False


def key_matches(key: str, selector: str, value: Any) -> bool:
    if key == "*":
        return True
    if selector == "count":
        return plural_key_matches(key, value)
    return str(value) == key


def render_pattern_body(body: str, context: dict[str, Any]) -> str:
    return PLACEHOLDER_RE.sub(lambda match: str(context.get(match.group("name"), "")), body)


def format_mf2_match(pattern: str, context: dict[str, Any]) -> str:
    """Evaluate the small MF2 matcher subset used by term form fixtures."""
    selectors_match = MATCH_RE.search(pattern)
    if not selectors_match:
        return render_pattern_body(pattern.strip("{} "), context)
    selectors = [part.removeprefix("$") for part in selectors_match.group("selectors").split()]
    declared_inputs = {match.group("name") for match in INPUT_RE.finditer(pattern)}
    missing = [selector for selector in selectors if selector not in declared_inputs]
    if missing:
        raise DiagnosticError("invalid-term-pattern", feature="input", value=",".join(missing))
    variants_source = pattern[selectors_match.end() :]
    fallback: str | None = None
    for variant in VARIANT_RE.finditer(variants_source):
        keys = variant.group("keys").split()
        if len(keys) != len(selectors):
            raise DiagnosticError("invalid-term-pattern", feature="variant-key-count", value=" ".join(keys))
        body = variant.group("body")
        if all(key == "*" for key in keys):
            fallback = body
        if all(key_matches(key, selector, context.get(selector, "*")) for key, selector in zip(keys, selectors)):
            return render_pattern_body(body, context)
    if fallback is not None:
        return render_pattern_body(fallback, context)
    raise DiagnosticError("missing-term-form", feature="forms.default")


def is_resource_id(value: Any) -> bool:
    return isinstance(value, str) and "." in value


class LocaleAdapter:
    def call(
        self,
        function_name: str,
        value: BundleValue,
        options: dict[str, str],
        args: dict[str, str],
        bundle: GrammarBundle,
    ) -> str:
        options = resolve_scalar_options(options, args)
        if function_name in {"term", "article"} and value.kind != "term":
            raise DiagnosticError("invalid-argument-type", argument=value.id, expected="term")
        if function_name == "term":
            return self.term(value, options)
        if function_name == "person":
            if value.kind != "person":
                raise DiagnosticError("invalid-argument-type", argument=value.id, expected="person")
            return value.text
        if function_name == "article":
            return self.article(value, options)
        if function_name == "agree":
            target = bundle.resolve(args[options["with"]])
            return self.agree(value, target, options)
        if function_name == "verb":
            target = bundle.resolve(args[options["with"]]) if "with" in options else value
            return self.verb(value, target, options)
        if function_name == "count":
            return self.count(value, options, args, bundle)
        raise ValueError(f"Unsupported function :{function_name}")

    def term(self, value: BundleValue, options: dict[str, str]) -> str:
        if value.forms.get("default"):
            return format_mf2_match(value.forms["default"], option_context(options, value))
        return value.text

    def article(self, value: BundleValue, options: dict[str, str]) -> str:
        raise NotImplementedError

    def agree(self, value: BundleValue, target: BundleValue, options: dict[str, str]) -> str:
        raise NotImplementedError

    def verb(self, value: BundleValue, target: BundleValue, options: dict[str, str]) -> str:
        raise NotImplementedError

    def count(
        self,
        value: BundleValue,
        options: dict[str, str],
        args: dict[str, str],
        bundle: GrammarBundle,
    ) -> str:
        return value.text


class FrenchAdapter(LocaleAdapter):
    def term(self, value: BundleValue, options: dict[str, str]) -> str:
        if value.forms.get("default"):
            return format_mf2_match(value.forms["default"], option_context(options, value))
        number = options.get("number")
        text = self.term_text(value, number)
        article = options.get("article")
        if article == "definite":
            return self.article(value, {"type": "definite", "number": number, "fallback": options.get("fallback", "")}) + text
        if article == "indefinite":
            return self.article(value, {"type": "indefinite", "number": number, "fallback": options.get("fallback", "")}) + text
        return text

    def term_text(self, value: BundleValue, number: str | None) -> str:
        if number == "plural":
            return value.morphology.get("forms", {}).get("plural", f"{value.text}s")
        return value.text

    def article(self, value: BundleValue, options: dict[str, str]) -> str:
        morphology = value.morphology
        gender = morphology.get("gender")
        number = options.get("number") or morphology.get("number")
        phonology = morphology.get("phonology", {})
        article_type = options.get("type", "definite")
        if number == "plural":
            return "les " if article_type == "definite" else "des "
        if article_type == "indefinite":
            return "une " if gender == "feminine" else "un "
        if options.get("fallback") == "error" and "elides" not in phonology:
            raise DiagnosticError("missing-morphology", feature="phonology.elides")
        if phonology.get("elides"):
            return "l'"
        return "la " if gender == "feminine" else "le "

    def agree(self, value: BundleValue, target: BundleValue, options: dict[str, str]) -> str:
        forms = value.morphology.get("forms", {})
        target_number = options.get("number") or target.morphology.get("number")
        key = f"{target.morphology.get('gender')}.{target_number}"
        return forms.get(key, value.text)

    def count(
        self,
        value: BundleValue,
        options: dict[str, str],
        args: dict[str, str],
        bundle: GrammarBundle,
    ) -> str:
        count = int(value.text)
        target = bundle.resolve(args[options["of"]])
        number = "plural" if count != 1 else "singular"
        if target.forms.get("default"):
            return self.term(target, {"usage": "count", "number": number, "count": str(count)})
        item = self.term(target, {"number": number})
        return f"{count} {item}"


class GermanAdapter(LocaleAdapter):
    DEFINITE_ARTICLES = {
        ("masculine", "nominative"): "der",
        ("masculine", "accusative"): "den",
        ("masculine", "dative"): "dem",
        ("feminine", "nominative"): "die",
        ("feminine", "accusative"): "die",
        ("feminine", "dative"): "der",
        ("neuter", "nominative"): "das",
        ("neuter", "accusative"): "das",
        ("neuter", "dative"): "dem",
    }

    def term(self, value: BundleValue, options: dict[str, str]) -> str:
        if value.forms.get("default"):
            return format_mf2_match(value.forms["default"], option_context(options, value))
        article = options.get("article")
        if article == "definite":
            return f"{self.article(value, options)} {value.text}"
        return value.text

    def article(self, value: BundleValue, options: dict[str, str]) -> str:
        case = options.get("case", "nominative")
        gender = value.morphology.get("gender")
        try:
            return self.DEFINITE_ARTICLES[(gender, case)]
        except KeyError as exc:
            raise DiagnosticError("unsupported-option-value", option="case", value=case) from exc


class RussianAdapter(LocaleAdapter):
    def term(self, value: BundleValue, options: dict[str, str]) -> str:
        if value.forms.get("default"):
            return format_mf2_match(value.forms["default"], option_context(options, value))
        case = options.get("case")
        if case:
            return value.morphology.get("forms", {}).get("cases", {}).get(case, value.text)
        return value.text


class ArabicAdapter(LocaleAdapter):
    def verb(self, value: BundleValue, target: BundleValue, options: dict[str, str]) -> str:
        target_morphology = target.morphology
        key = ".".join(
            [
                options.get("tense", "present"),
                target_morphology.get("person", "third"),
                target_morphology.get("gender", "unknown"),
                target_morphology.get("number", "singular"),
            ]
        )
        return value.morphology.get("forms", {}).get(key, value.text)


class SwahiliAdapter(LocaleAdapter):
    def agree(self, value: BundleValue, target: BundleValue, options: dict[str, str]) -> str:
        agreement_class = target.morphology.get("agreementClass")
        return value.morphology.get("forms", {}).get(f"agreement.{agreement_class}", value.text)


class JapaneseAdapter(LocaleAdapter):
    def count(
        self,
        value: BundleValue,
        options: dict[str, str],
        args: dict[str, str],
        bundle: GrammarBundle,
    ) -> str:
        target = bundle.resolve(args[options["of"]])
        classifier = target.morphology.get("classifier", "")
        return f"{value.text}{classifier}"


class KoreanAdapter(LocaleAdapter):
    def verb(self, value: BundleValue, target: BundleValue, options: dict[str, str]) -> str:
        politeness = options.get("politeness")
        return value.morphology.get("forms", {}).get(f"politeness.{politeness}", value.text)


class WelshAdapter(LocaleAdapter):
    def term(self, value: BundleValue, options: dict[str, str]) -> str:
        if value.forms.get("default"):
            return format_mf2_match(value.forms["default"], option_context(options, value))
        mutation = options.get("mutation")
        if mutation:
            return value.morphology.get("forms", {}).get("mutations", {}).get(mutation, value.text)
        return value.text


def adapter_for(locale: str) -> LocaleAdapter:
    adapters = {
        "fr": FrenchAdapter,
        "de": GermanAdapter,
        "ru": RussianAdapter,
        "ar": ArabicAdapter,
        "sw": SwahiliAdapter,
        "ja": JapaneseAdapter,
        "ko": KoreanAdapter,
        "cy": WelshAdapter,
    }
    try:
        return adapters[locale]()
    except KeyError as exc:
        raise ValueError(f"Unsupported locale: {locale}") from exc


def fixture_paths(root: Path) -> list[Path]:
    return sorted(root.glob("fixtures/**/*.json"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--root",
        type=Path,
        default=Path("dev-docs/experiments/mf2-grammar-agreement"),
    )
    parser.add_argument("--fixture", type=Path, action="append")
    parser.add_argument("--include-planned", action="store_true")
    args = parser.parse_args()

    paths = args.fixture if args.fixture else fixture_paths(args.root)
    failures: list[str] = []
    for path in paths:
        try:
            bundle = GrammarBundle(path)
        except ValueError as exc:
            if not args.include_planned and "Unsupported locale" in str(exc):
                print(f"planned {path} ({exc})")
                continue
            raise
        bundle_failures = bundle.validate_examples()
        if bundle_failures:
            failures.extend(bundle_failures)
        else:
            print(f"ok {path}")

    if failures:
        print("\n".join(failures))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
