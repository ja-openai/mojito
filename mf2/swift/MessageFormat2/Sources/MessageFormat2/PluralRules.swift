func selectCardinalPluralCategory(locale: String, value: MF2Value) -> String? {
    selectPluralCategory(locale: locale, value: value, numberSelect: .plural)
}

enum MF2NumberSelect: Equatable {
    case plural
    case ordinal
    case exact
}

func selectPluralCategory(
    locale: String,
    value: MF2Value,
    numberSelect: MF2NumberSelect = .plural
) -> String? {
    let raw: String
    switch value {
    case let .number(number), let .string(number):
        raw = number
    case .bool, .null:
        return nil
    }

    let fraction = raw.split(separator: ".", maxSplits: 1).dropFirst().first.map(String.init) ?? ""
    guard fraction.isEmpty || Int64(fraction) != nil else { return nil }
    // Generated CLDR operands currently use binary64 and signed integer fields.
    // Keep their integer conversion and arithmetic within the exact binary64 range.
    guard let number = Double(raw), number.isFinite, abs(number) <= 9_007_199_254_740_991,
        let operands = NumberOperands(raw)
    else {
        return nil
    }
    switch numberSelect {
    case .plural:
        return selectCardinal(locale: locale, operands: operands)
    case .ordinal:
        return selectOrdinal(locale: locale, operands: operands)
    case .exact:
        return nil
    }
}
