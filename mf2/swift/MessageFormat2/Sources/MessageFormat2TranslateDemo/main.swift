import Foundation
import MessageFormat2Runtime

private let catalog = try Catalog.load(
    URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        .appendingPathComponent("../../examples/catalog.json")
        .standardizedFileURL
)

private let examples: [(String, String, [String: MF2Value], String)] = [
    ("welcome", "fr", ["name": .string("Mojito")], "Bienvenue, Mojito !"),
    ("welcome", "fr-CA", ["name": .string("Mojito")], "Bienvenue, Mojito !"),
    ("checkout.total", "en", ["amount": .number("1234.5")], "Total: $1,234.50"),
    ("checkout.total", "fr", ["amount": .number("1234.5")], "Total : 1\u{202f}234,50 €"),
    ("cart.items", "en", ["count": .number("1")], "1 item"),
    ("cart.items", "en", ["count": .number("5")], "5 items"),
    ("cart.items", "ru", ["count": .number("2")], "2 предмета"),
    ("cart.items", "ru", ["count": .number("5")], "5 предметов"),
]

for (messageID, locale, arguments, expected) in examples {
    let actual = try catalog.translate(messageID, locale: locale, arguments: arguments)
    guard actual == expected else {
        throw DemoError.mismatch(messageID: messageID, locale: locale, expected: expected, actual: actual)
    }
    print("\(messageID)[\(locale)] -> \"\(actual)\"")
}

private struct Catalog: Decodable {
    let messages: [String: [String: MF2Message]]

    static func load(_ url: URL) throws -> Catalog {
        try JSONDecoder().decode(Catalog.self, from: Data(contentsOf: url))
    }

    func translate(
        _ messageID: String,
        locale: String,
        arguments: [String: MF2Value]
    ) throws -> String {
        let model = try model(messageID, locale: locale)
        return try model.format(arguments: arguments, locale: locale)
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
