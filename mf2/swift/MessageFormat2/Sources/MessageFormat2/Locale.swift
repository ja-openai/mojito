import Foundation

enum MF2LocaleKey {
    private static let maxLocaleKeyLength = 256

    static func canonicalKey(_ locale: String) -> String {
        guard locale.count <= maxLocaleKeyLength else {
            return ""
        }
        return parts(locale).joined(separator: "-")
    }

    static func lookupChain(_ locale: String) -> [String] {
        structuralLookupChain(canonicalKey(locale))
    }

    static func lookup<T>(
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

    static func pluralLookupChain(_ locale: String, parents: [String: String]) -> [String] {
        featureLookupChain(locale, parents: parents)
    }

    static func featureLookupChain(_ locale: String, parents: [String: String]) -> [String] {
        var chain: [String] = []
        appendFeatureLookupChain(canonicalKey(locale), parents: parents, chain: &chain)
        return chain
    }

    private static func appendFeatureLookupChain(_ locale: String, parents: [String: String], chain: inout [String]) {
        var current = locale
        while !current.isEmpty {
            if current.count > maxLocaleKeyLength { return }
            if chain.contains(current) { return }
            chain.append(current)
            if let parent = parents[current] {
                appendFeatureLookupChain(parent, parents: parents, chain: &chain)
            }
            current = structuralParent(current) ?? ""
        }
    }

    private static func structuralLookupChain(_ locale: String) -> [String] {
        let parts = locale.split(separator: "-").map(String.init)
        return stride(from: parts.count, through: 1, by: -1)
            .map { parts.prefix($0).joined(separator: "-") }
    }

    private static func parts(_ locale: String) -> [String] {
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
        return canonicalParts
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

    private static func structuralParent(_ locale: String) -> String? {
        guard let index = locale.lastIndex(of: "-") else { return nil }
        return String(locale[..<index])
    }
}
