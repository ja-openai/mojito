import Foundation
import MessageFormat2

let registry = MF2FunctionRegistry.foundation

let examples: [(label: String, source: String, locale: String, arguments: [String: MF2Value])] = [
    ("number", "{$value :number}", "fr", ["value": .number("12345.678")]),
    ("percent", "{$value :percent maximumFractionDigits=1}", "ar", ["value": .number("0.1234")]),
    ("currency", "{$value :currency currency=EUR}", "ja", ["value": .number("9876.5")]),
    ("date", "{$value :date dateStyle=full timeZone=UTC}", "ja", ["value": .string("2026-05-21")]),
    ("time", "{$value :time timeStyle=medium timeZone=UTC}", "en", ["value": .string("2026-05-21T14:30:15Z")]),
    ("datetime", "{$value :datetime dateStyle=medium timeStyle=medium timeZone=UTC}", "fr", ["value": .string("2026-05-21T14:30:15Z")]),
]

for example in examples {
    let model = try requireModel(parseToModel(example.source), label: example.label)
    let result = try formatMessage(
        model,
        arguments: example.arguments,
        locale: example.locale,
        functions: registry
    )
    guard result.ok else {
        throw DemoError.formatErrors(example.label, result.errors.map(\.code))
    }
    print("\(example.label)[\(example.locale)] -> \(result.value)")
}

let selectionCases: [(function: String, locale: String, value: MF2Value, expected: String)] = [
    (":number", "fr", .number("1000000"), "many"),
    (":number minimumFractionDigits=1", "ru", .number("1"), "other"),
    (":integer", "fr", .number("1000000.9"), "many"),
    (":percent", "fr", .number("10000"), "many"),
]
for item in selectionCases {
    let source = ".input {$value \(item.function)}\n.match $value\n"
        + "zero {{zero}}\none {{one}}\ntwo {{two}}\nfew {{few}}\n"
        + "many {{many}}\nother {{other}}\n* {{other}}"
    let model = try requireModel(parseToModel(source), label: "selection")
    let result = try formatMessage(
        model,
        arguments: ["value": item.value],
        locale: item.locale,
        functions: registry
    )
    guard result.ok, result.value == item.expected else {
        throw DemoError.selectionMismatch(item.locale, item.expected, result.value, result.errors.map(\.code))
    }
}
let offsetSelectionSource = ".input {$value :integer}\n.local $adjusted = {$value :offset subtract=1}\n"
    + ".match $adjusted\n"
    + "zero {{zero}}\none {{one}}\ntwo {{two}}\nfew {{few}}\n"
    + "many {{many}}\nother {{other}}\n* {{other}}"
let offsetSelection = try requireModel(parseToModel(offsetSelectionSource), label: "offset-selection")
let offsetResult = try formatMessage(
    offsetSelection,
    arguments: ["value": .number("1000001")],
    locale: "fr",
    functions: registry
)
guard offsetResult.ok, offsetResult.value == "many" else {
    throw DemoError.selectionMismatch("fr", "many", offsetResult.value, offsetResult.errors.map(\.code))
}

try runFoundationDifferentialChecks(registry)

#if os(macOS) || os(iOS) || os(tvOS) || os(watchOS) || os(visionOS)
    let relativeTime = try requireModel(
        parseToModel("{$value :relativeTime unit=day numeric=auto style=long}"),
        label: "relativeTime"
    )
    for locale in ["en", "fr", "ja", "ar"] {
        let result = try formatMessage(
            relativeTime,
            arguments: ["value": .number("-1")],
            locale: locale,
            functions: registry
        )
        guard result.ok else {
            throw DemoError.formatErrors("relativeTime[\(locale)]", result.errors.map(\.code))
        }
        print("relativeTime[\(locale)] -> \(result.value)")
    }
#else
    print("relativeTime -> deferred on this Swift platform")
#endif

private func runFoundationDifferentialChecks(_ registry: MF2FunctionRegistry) throws {
    let floatingTemporalCases: [(label: String, value: String, function: String)] = [
        ("date-only", "2026-05-21", ":date dateStyle=short"),
        (
            "floating-datetime",
            "2026-05-21T14:30:15",
            ":datetime dateStyle=short timeStyle=short"
        ),
    ]
    for item in floatingTemporalCases {
        let utc = try formatProbe(
            "{|\(item.value)| \(item.function) timeZone=UTC}",
            label: "\(item.label)-utc",
            registry: registry
        )
        let losAngeles = try formatProbe(
            "{|\(item.value)| \(item.function) timeZone=|America/Los_Angeles|}",
            label: "\(item.label)-los-angeles",
            registry: registry
        )
        try expectProbe(item.label, losAngeles, value: utc.value)
    }

    let zonedSource = "{|2026-05-21T14:30:15Z| :datetime dateStyle=short timeStyle=short"
    let zonedUtc = try formatProbe(
        "\(zonedSource) timeZone=UTC}",
        label: "zoned-utc",
        registry: registry
    )
    let zonedLosAngeles = try formatProbe(
        "\(zonedSource) timeZone=|America/Los_Angeles|}",
        label: "zoned-los-angeles",
        registry: registry
    )
    guard zonedUtc.ok, zonedLosAngeles.ok, zonedUtc.value != zonedLosAngeles.value else {
        throw DemoError.formatMismatch(
            "zoned-datetime",
            "an instant shifted from \(zonedUtc.value)",
            zonedLosAngeles.value,
            zonedLosAngeles.errors.map(\.code)
        )
    }

    let inheritedNumber = try formatProbe(
        ".local $n = {1.2 :number minimumFractionDigits=2} {{Value {$n :number}}}",
        label: "inherited-number-options",
        registry: registry
    )
    try expectProbe("inherited-number-options", inheritedNumber, value: "Value 1.20")

    let defaultCurrency = try formatProbe(
        "{42 :currency currency=EUR}",
        label: "currency-default-fraction",
        registry: registry
    )
    let autoCurrency = try formatProbe(
        "{42 :currency currency=EUR fractionDigits=auto}",
        label: "currency-auto-fraction",
        registry: registry
    )
    try expectProbe("currency-auto-fraction", autoCurrency, value: defaultCurrency.value)

    let missingCurrency = try formatProbe(
        "{42 :currency}",
        label: "currency-missing-option",
        registry: registry
    )
    try expectProbe("currency-missing-option", missingCurrency, errors: ["bad-operand"])

    let directCurrencySource = try formatProbe(
        "{42 :currency currency=USD}",
        label: "currency-source-direct",
        registry: registry
    )
    let inheritedCurrencySource = try formatProbe(
        ".local $n = {42 :currency currency=USD} {{{$n :currency}}}",
        label: "currency-source-inherited",
        registry: registry
    )
    try expectProbe(
        "currency-source-inherited",
        inheritedCurrencySource,
        value: directCurrencySource.value
    )

    let replacementCurrencyAfterNumber = try formatProbe(
        ".local $n = {42 :currency currency=USD} "
            + ".local $plain = {$n :number} "
            + "{{{$plain :currency currency=EUR}}}",
        label: "replacement-currency-after-number",
        registry: registry
    )
    try expectProbe(
        "replacement-currency-after-number",
        replacementCurrencyAfterNumber,
        value: defaultCurrency.value
    )

    let overriddenCurrencySource = try formatProbe(
        ".local $n = {42 :currency currency=USD} {{{$n :currency currency=EUR}}}",
        label: "currency-source-override",
        registry: registry
    )
    try expectProbe(
        "currency-source-override",
        overriddenCurrencySource,
        errors: ["bad-option"]
    )

    let currencySelector = try formatProbe(
        ".local $n = {42 :currency currency=EUR} .match $n * {{other}}",
        label: "currency-selector",
        registry: registry
    )
    try expectProbe(
        "currency-selector",
        currencySelector,
        value: "other",
        errors: ["bad-selector"]
    )

    #if os(macOS) || os(iOS) || os(tvOS) || os(watchOS) || os(visionOS)
        let relativeTimeChain = try formatProbe(
            ".local $value = {1000 :number} "
                + "{{{$value :relativeTime unit=day numeric=always style=long}}}",
            label: "relativeTime-chain",
            locale: "fr",
            registry: registry
        )
        guard relativeTimeChain.ok, relativeTimeChain.value != "{$value}" else {
            throw DemoError.formatMismatch(
                "relativeTime-chain[fr]",
                "a localized relative time",
                relativeTimeChain.value,
                relativeTimeChain.errors.map(\.code)
            )
        }
    #endif
}

private func formatProbe(
    _ source: String,
    label: String,
    locale: String = "en-US",
    registry: MF2FunctionRegistry
) throws -> MF2FormatResult {
    let model = try requireModel(parseToModel(source), label: label)
    return try formatMessage(model, locale: locale, functions: registry)
}

private func expectProbe(
    _ label: String,
    _ result: MF2FormatResult,
    value: String? = nil,
    errors: [String] = []
) throws {
    let actualErrors = result.errors.map(\.code)
    guard (value == nil || result.value == value), actualErrors == errors else {
        throw DemoError.formatMismatch(
            label,
            value ?? "errors=\(errors)",
            result.value,
            actualErrors
        )
    }
}

private func requireModel(_ result: MF2ParseResult, label: String) throws -> MF2Message {
    guard let model = result.model else {
        throw DemoError.parseFailed(label, result.diagnostics.map(\.message))
    }
    return model
}

private enum DemoError: Error, CustomStringConvertible {
    case parseFailed(String, [String])
    case formatErrors(String, [String])
    case selectionMismatch(String, String, String, [String])
    case formatMismatch(String, String, String, [String])

    var description: String {
        switch self {
        case let .parseFailed(label, diagnostics):
            "\(label) parse failed: \(diagnostics)"
        case let .formatErrors(label, errors):
            "\(label) format errors: \(errors)"
        case let .selectionMismatch(locale, expected, actual, errors):
            "selection[\(locale)] expected \(expected), got \(actual); errors=\(errors)"
        case let .formatMismatch(label, expected, actual, errors):
            "\(label) expected \(expected), got \(actual); errors=\(errors)"
        }
    }
}
