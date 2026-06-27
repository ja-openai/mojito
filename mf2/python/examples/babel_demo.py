from __future__ import annotations

from mojito_mf2 import format_message, parse_to_model
from mojito_mf2.babel import babel_function_registry


CATALOG = {
    "metrics": (
        "Number {$amount :number maximumFractionDigits=2}; "
        "percent {$ratio :percent maximumFractionDigits=1}; "
        "currency {$price :currency currency=EUR}"
    ),
    "instant": (
        "Date {$instant :date dateStyle=full timeZone=UTC}; "
        "time {$instant :time timeStyle=medium timeZone=UTC}; "
        "datetime {$instant :datetime dateStyle=medium timeStyle=medium timeZone=UTC}"
    ),
    "relative": "Due {$delta :relativeTime unit=day numeric=always}",
}


def main() -> None:
    functions = babel_function_registry()
    arguments = {
        "amount": 12345.678,
        "ratio": 0.1234,
        "price": 9876.5,
        "instant": "2026-05-21T14:30:15+00:00",
        "delta": -3,
    }
    for locale in ["en-US", "fr-FR", "ja-JP", "ar-EG"]:
        for name, source in CATALOG.items():
            parsed = parse_to_model(source)
            if parsed.has_diagnostics:
                raise RuntimeError(f"{name}: {parsed.diagnostics}")
            print(
                f"{locale} {name} -> "
                f"{format_message(parsed.model, arguments, locale=locale, functions=functions).value}"
            )


if __name__ == "__main__":
    main()
