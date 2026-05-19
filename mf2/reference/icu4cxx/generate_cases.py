from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if len(args) != 2:
        print("Usage: generate_cases.py <fixture-dir> <output.hpp>", file=sys.stderr)
        return 2

    fixture_dir = Path(args[0])
    output = Path(args[1])
    cases: list[dict[str, Any]] = []
    for fixture_path in sorted(fixture_dir.glob("*.json")):
        with fixture_path.open(encoding="utf-8") as file:
            fixture = json.load(file)
        for format_case in fixture.get("formatCases", []):
            cases.append(
                {
                    "name": f"{fixture['name']}[{format_case.get('locale', 'en')}]",
                    "locale": format_case.get("locale", "en"),
                    "source": fixture["source"],
                    "expected": format_case["expected"],
                    "arguments": format_case.get("arguments", {}),
                }
            )

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(render(cases), encoding="utf-8")
    return 0


def render(cases: list[dict[str, Any]]) -> str:
    lines = [
        "#pragma once",
        "",
        "#include <cstdint>",
        "#include <string>",
        "#include <vector>",
        "",
        "enum class GeneratedArgType { String, Int, Double, Bool };",
        "",
        "struct GeneratedArg {",
        "    std::string name;",
        "    GeneratedArgType type;",
        "    std::string stringValue;",
        "    int64_t intValue;",
        "    double doubleValue;",
        "    bool boolValue;",
        "};",
        "",
        "struct GeneratedCase {",
        "    std::string name;",
        "    std::string locale;",
        "    std::string source;",
        "    std::string expected;",
        "    std::vector<GeneratedArg> arguments;",
        "};",
        "",
        "static std::vector<GeneratedCase> generatedCases() {",
        "    std::vector<GeneratedCase> cases;",
    ]
    for case in cases:
        lines.append("    cases.push_back({")
        lines.append(f"        {cpp_string(case['name'])},")
        lines.append(f"        {cpp_string(case['locale'])},")
        lines.append(f"        {cpp_string(case['source'])},")
        lines.append(f"        {cpp_string(case['expected'])},")
        lines.append("        {")
        for name, value in case["arguments"].items():
            lines.append(f"            {render_arg(name, value)},")
        lines.append("        }")
        lines.append("    });")
    lines.extend(["    return cases;", "}", ""])
    return "\n".join(lines)


def render_arg(name: str, value: Any) -> str:
    if isinstance(value, bool):
        return (
            f"GeneratedArg{{{cpp_string(name)}, GeneratedArgType::Bool, \"\", 0, 0.0, "
            f"{str(value).lower()}}}"
        )
    if isinstance(value, int):
        return (
            f"GeneratedArg{{{cpp_string(name)}, GeneratedArgType::Int, \"\", {value}, 0.0, false}}"
        )
    if isinstance(value, float):
        return (
            f"GeneratedArg{{{cpp_string(name)}, GeneratedArgType::Double, \"\", 0, "
            f"{repr(value)}, false}}"
        )
    return (
        f"GeneratedArg{{{cpp_string(name)}, GeneratedArgType::String, "
        f"{cpp_string(str(value))}, 0, 0.0, false}}"
    )


def cpp_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


if __name__ == "__main__":
    raise SystemExit(main())
