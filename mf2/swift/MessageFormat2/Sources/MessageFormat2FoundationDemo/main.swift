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

let oversizedTimeZone = String(repeating: "A", count: 257)
let oversizedTimeZoneModel = try requireModel(
    parseToModel("{$value :datetime dateStyle=medium timeStyle=medium timeZone=\(oversizedTimeZone)}"),
    label: "oversizedTimeZone"
)
let oversizedTimeZoneResult = try formatMessage(
    oversizedTimeZoneModel,
    arguments: ["value": .string("2020-01-02T03:04:05Z")],
    locale: "en",
    functions: registry
)
guard oversizedTimeZoneResult.errors.map(\.code) == ["bad-option"] else {
    throw DemoError.formatErrors("oversizedTimeZone", oversizedTimeZoneResult.errors.map(\.code))
}

let oversizedStyle = String(repeating: "A", count: 257)
let oversizedStyleModel = try requireModel(
    parseToModel("{$value :datetime style=\(oversizedStyle) timeZone=UTC}"),
    label: "oversizedStyle"
)
let oversizedStyleResult = try formatMessage(
    oversizedStyleModel,
    arguments: ["value": .string("2020-01-02T03:04:05Z")],
    locale: "en",
    functions: registry
)
guard oversizedStyleResult.errors.map(\.code) == ["bad-option"] else {
    throw DemoError.formatErrors("oversizedStyle", oversizedStyleResult.errors.map(\.code))
}

let oversizedSignDisplayModel = try requireModel(
    parseToModel("{$value :number signDisplay=\(oversizedStyle)}"),
    label: "oversizedSignDisplay"
)
let oversizedSignDisplayResult = try formatMessage(
    oversizedSignDisplayModel,
    arguments: ["value": .number("1")],
    locale: "en",
    functions: registry
)
guard oversizedSignDisplayResult.errors.map(\.code) == ["bad-option"] else {
    throw DemoError.formatErrors("oversizedSignDisplay", oversizedSignDisplayResult.errors.map(\.code))
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

    var description: String {
        switch self {
        case let .parseFailed(label, diagnostics):
            "\(label) parse failed: \(diagnostics)"
        case let .formatErrors(label, errors):
            "\(label) format errors: \(errors)"
        }
    }
}
