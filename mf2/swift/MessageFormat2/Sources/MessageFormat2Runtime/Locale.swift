import Foundation

public enum MF2LocaleID {
    public static func canonicalKey(_ locale: String) -> String {
        locale
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "_")
            .lowercased()
    }

    public static func lookupChain(_ locale: String) -> [String] {
        let parts = canonicalKey(locale)
            .split(separator: "_")
            .map(String.init)
        return stride(from: parts.count, through: 1, by: -1)
            .map { parts.prefix($0).joined(separator: "_") }
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
}
