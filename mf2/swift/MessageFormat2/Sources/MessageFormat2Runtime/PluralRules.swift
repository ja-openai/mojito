func selectCardinalPluralCategory(locale: String, value: MF2Value) -> String? {
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
    return selectCardinal(locale: locale, operands: operands)
}
