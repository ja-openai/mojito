import Foundation

let mf2MaximumSafeInt64Double = 9_000_000_000_000_000_000.0

func mf2Int64StringIfSafe(_ value: Double) -> String? {
    guard value.isFinite,
          value.rounded(.towardZero) == value,
          abs(value) <= mf2MaximumSafeInt64Double
    else {
        return nil
    }
    return String(Int64(value))
}

func mf2FormatWholeDoublePlain(_ value: Double) -> String {
    mf2Int64StringIfSafe(value)
        ?? String(format: "%.0f", locale: Locale(identifier: "en_US_POSIX"), value)
}

func mf2FormatWholeDoubleNative(_ value: Double) -> String {
    mf2Int64StringIfSafe(value) ?? String(value)
}
