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

    guard let operands = NumberOperands(raw) else {
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
