import Foundation
import MessageFormat2Runtime

private let catalog = try Catalog.load(
    URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        .appendingPathComponent("../../examples/catalog.json")
        .standardizedFileURL
)
private let functions = demoFunctionRegistry()

private let examples: [(String, String, [String: MF2Value], MF2BidiIsolation, String)] = [
    ("welcome", "fr", ["name": .string("Mojito")], .none, "Bienvenue, Mojito !"),
    ("welcome", "fr-CA", ["name": .string("Mojito")], .none, "Bienvenue, Mojito !"),
    ("checkout.total", "en", ["amount": .number("1234.5")], .none, "Total: $1,234.50"),
    ("checkout.total", "fr", ["amount": .number("1234.5")], .none, "Total : 1\u{202f}234,50 €"),
    ("debug.raw", "en", ["value": .number("1234.5")], .none, "Raw: number=1234.5"),
    ("file.saved", "en", ["fileName": .string("שלום.txt")], .default, "File \u{2068}שלום.txt\u{2069} saved."),
    ("cart.items", "en", ["count": .number("1")], .none, "1 item"),
    ("cart.items", "en", ["count": .number("5")], .none, "5 items"),
    ("cart.items", "ru", ["count": .number("2")], .none, "2 предмета"),
    ("cart.items", "ru", ["count": .number("5")], .none, "5 предметов"),
    ("assignee.files", "en", ["gender": .string("male"), "count": .number("1")], .none, "He reviewed 1 file"),
    ("assignee.files", "en", ["gender": .string("female"), "count": .number("3")], .none, "She reviewed 3 files"),
    ("assignee.files", "en", ["gender": .string("unknown"), "count": .number("2")], .none, "They reviewed 2 files"),
]

for (messageID, locale, arguments, bidiIsolation, expected) in examples {
    let actual = try catalog.translate(
        messageID,
        locale: locale,
        arguments: arguments,
        functions: functions,
        bidiIsolation: bidiIsolation
    )
    guard actual == expected else {
        throw DemoError.mismatch(messageID: messageID, locale: locale, expected: expected, actual: actual)
    }
    print("\(messageID)[\(locale)] -> \"\(actual)\"")
    if bidiIsolation != .none {
        print("\(messageID)[\(locale)].escaped -> \"\(escapedNonASCII(actual))\"")
    }
}

private struct Catalog: Decodable {
    let messages: [String: [String: MF2Message]]

    static func load(_ url: URL) throws -> Catalog {
        try JSONDecoder().decode(Catalog.self, from: Data(contentsOf: url))
    }

    func translate(
        _ messageID: String,
        locale: String,
        arguments: [String: MF2Value],
        functions: MF2FunctionRegistry,
        bidiIsolation: MF2BidiIsolation = .none
    ) throws -> String {
        let model = try model(messageID, locale: locale)
        return try model.format(
            arguments: arguments,
            locale: locale,
            functions: functions,
            bidiIsolation: bidiIsolation
        )
    }

    private func model(_ messageID: String, locale: String) throws -> MF2Message {
        guard let localized = messages[messageID] else {
            throw DemoError.missingMessage(messageID)
        }
        guard let fallback = MF2LocaleKey.lookup(in: localized, locale: locale) else {
            throw DemoError.missingLocale(messageID: messageID, locale: locale)
        }
        return fallback
    }
}

private func demoFunctionRegistry() -> MF2FunctionRegistry {
    MF2FunctionRegistry.defaults
        .withFunction("currency", formatter: formatCurrency)
        .withFunction("rawType", formatter: formatRawType)
}

private func formatCurrency(_ call: MF2FunctionCall) throws -> String {
    let currency = try call.optionValue("currency", default: "USD") ?? "USD"
    return try formatCurrencyValue(value: call.value, currency: currency, locale: call.locale)
}

private func formatRawType(_ call: MF2FunctionCall) throws -> String {
    let kind: String
    switch call.rawValue {
    case .number:
        kind = "number"
    case .bool:
        kind = "bool"
    case .null:
        kind = "null"
    case .string:
        kind = "string"
    }
    return "\(kind)=\(call.value)"
}

private func formatCurrencyValue(value: String, currency: String, locale: String) throws -> String {
    guard let amount = Double(value) else {
        throw MF2Error.badOperand("Currency value must be numeric, got \(value).")
    }
    guard amount.isFinite else {
        throw MF2Error.badOperand("Currency value must be finite.")
    }

    let currency = currency.uppercased()
    let fractionDigits = currencyFractionDigits(currency)
    let scale = Int(pow(10.0, Double(fractionDigits)))
    let rounded = Int((abs(amount) * Double(scale)).rounded())
    let major = rounded / scale
    let fraction = rounded % scale
    let french = canonicalLocalePrefix(locale) == "fr"
    let grouped = groupDigits(String(major), separator: french ? "\u{202f}" : ",")
    let number: String
    if fractionDigits == 0 {
        number = grouped
    } else {
        number = "\(grouped)\(french ? "," : ".")\(String(format: "%0\(fractionDigits)d", fraction))"
    }
    let symbol = currencySymbol(currency, french: french)
    let negative = amount < 0 ? "-" : ""

    if french {
        return "\(negative)\(number) \(symbol)"
    }
    if symbol.count == 3 {
        return "\(negative)\(symbol) \(number)"
    }
    return "\(negative)\(symbol)\(number)"
}

private func currencyFractionDigits(_ currency: String) -> Int {
    switch currency {
    case "JPY", "KRW":
        0
    default:
        2
    }
}

private func currencySymbol(_ currency: String, french: Bool) -> String {
    switch currency {
    case "USD":
        french ? "$US" : "$"
    case "EUR":
        "€"
    case "JPY":
        "¥"
    case "GBP":
        "£"
    default:
        currency
    }
}

private func canonicalLocalePrefix(_ locale: String) -> String {
    locale.replacingOccurrences(of: "_", with: "-")
        .split(separator: "-", maxSplits: 1)
        .first
        .map { String($0).lowercased() } ?? "en"
}

private func groupDigits(_ digits: String, separator: String) -> String {
    var remaining = digits
    var groups: [String] = []
    while remaining.count > 3 {
        groups.append(String(remaining.suffix(3)))
        remaining.removeLast(3)
    }
    groups.append(remaining)
    return groups.reversed().joined(separator: separator)
}

private func escapedNonASCII(_ value: String) -> String {
    value.unicodeScalars.map { scalar in
        if scalar.value >= 0x20, scalar.value <= 0x7E {
            return String(scalar)
        }
        return String(format: "\\u%04X", scalar.value)
    }.joined()
}

private enum DemoError: Error, CustomStringConvertible {
    case missingMessage(String)
    case missingLocale(messageID: String, locale: String)
    case mismatch(messageID: String, locale: String, expected: String, actual: String)

    var description: String {
        switch self {
        case let .missingMessage(messageID):
            "Missing message id: \(messageID)"
        case let .missingLocale(messageID, locale):
            "Missing locale \(locale) and fallback en for \(messageID)"
        case let .mismatch(messageID, locale, expected, actual):
            "\(messageID)[\(locale)]: expected '\(expected)', got '\(actual)'"
        }
    }
}
