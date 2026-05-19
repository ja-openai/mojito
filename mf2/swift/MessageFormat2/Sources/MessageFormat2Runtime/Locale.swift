import Foundation

public struct MF2LocaleID: Equatable, Hashable {
    public let parts: [String]

    public init(_ locale: String) {
        let rawParts = locale
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "_", with: "-")
            .split(separator: "-")
            .map(String.init)

        var canonicalParts: [String] = []
        for (index, part) in rawParts.enumerated() {
            if part.count == 1 {
                break
            }
            canonicalParts.append(Self.canonicalSubtag(index: index, part: part))
        }
        parts = canonicalParts
    }

    public var canonicalTag: String {
        parts.joined(separator: "-")
    }

    public var lookupChain: [String] {
        stride(from: parts.count, through: 1, by: -1)
            .map { parts.prefix($0).joined(separator: "-") }
    }

    public static func canonicalKey(_ locale: String) -> String {
        MF2LocaleID(locale).canonicalTag
    }

    public static func lookupChain(_ locale: String) -> [String] {
        MF2LocaleID(locale).lookupChain
    }

    public static func lookup<T>(
        in values: [String: T],
        locale: String,
        fallback: String = "en"
    ) -> T? {
        var canonicalValues: [String: T] = [:]
        for (key, value) in values {
            canonicalValues[canonicalKey(key)] = value
        }
        for candidate in lookupChain(locale) {
            if let value = canonicalValues[candidate] {
                return value
            }
        }
        return canonicalValues[canonicalKey(fallback)]
    }

    private static func canonicalSubtag(index: Int, part: String) -> String {
        if index == 0 {
            return part.lowercased()
        }
        if part.count == 4, part.allSatisfy(\.isLetter) {
            return part.prefix(1).uppercased() + part.dropFirst().lowercased()
        }
        if (part.count == 2 && part.allSatisfy(\.isLetter))
            || (part.count == 3 && part.allSatisfy(\.isNumber))
        {
            return part.uppercased()
        }
        return part.lowercased()
    }
}
