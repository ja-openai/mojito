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
