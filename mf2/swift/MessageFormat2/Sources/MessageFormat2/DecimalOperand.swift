// Decimal arithmetic for the portable profile. Coefficients stay textual so formatting
// and selection never pass through binary floating point or Foundation Decimal.
struct DecimalOperand {
    static let maximumDigits = 4096
    var negative: Bool
    var digits: [UInt8]
    var scale: Int

    init?(_ text: String) {
        let limit = Self.maximumDigits * 2 + 32
        guard text.utf8.prefix(limit + 1).count <= limit else { return nil }
        let bytes = Array(text.utf8)
        var index = 0
        negative = bytes.first == 45
        if negative { index += 1 }
        let start = index
        guard index < bytes.count else { return nil }
        if bytes[index] == 48 {
            index += 1
        } else {
            guard (49...57).contains(bytes[index]) else { return nil }
            while index < bytes.count, (48...57).contains(bytes[index]) { index += 1 }
        }
        digits = bytes[start..<index].map { $0 - 48 }
        var fraction = 0
        if index < bytes.count, bytes[index] == 46 {
            index += 1
            let start = index
            while index < bytes.count, (48...57).contains(bytes[index]) { index += 1 }
            fraction = index - start
            guard fraction > 0 else { return nil }
            digits.append(contentsOf: bytes[start..<index].map { $0 - 48 })
        }
        var exponent = 0
        if index < bytes.count, bytes[index] == 101 || bytes[index] == 69 {
            index += 1
            var exponentNegative = false
            if index < bytes.count, bytes[index] == 43 || bytes[index] == 45 {
                exponentNegative = bytes[index] == 45
                index += 1
            }
            let start = index
            while index < bytes.count, (48...57).contains(bytes[index]) {
                exponent = exponent * 10 + Int(bytes[index] - 48)
                guard exponent <= Self.maximumDigits else { return nil }
                index += 1
            }
            guard index > start else { return nil }
            if exponentNegative { exponent = -exponent }
        }
        guard index == bytes.count, digits.count <= Self.maximumDigits else { return nil }
        scale = fraction - exponent
        if digits.allSatisfy({ $0 == 0 }) {
            negative = false
            digits = [0]
            scale = 0
            return
        }
        trim()
        if scale < 0 {
            guard digits.count - scale <= Self.maximumDigits else { return nil }
            digits.append(contentsOf: repeatElement(0, count: -scale))
            scale = 0
        }
        guard scale < Self.maximumDigits else { return nil }
        trim()
    }

    mutating func trim() {
        let first = digits.firstIndex(where: { $0 != 0 }) ?? (digits.count - 1)
        if first > 0 { digits.removeFirst(first) }
        if digits == [0] { negative = false }
    }

    var canonical: String {
        if digits.allSatisfy({ $0 == 0 }) { return "0" }
        var bytes = digits.map { $0 + 48 }
        if scale > 0 {
            if bytes.count <= scale {
                bytes.insert(contentsOf: repeatElement(48, count: scale + 1 - bytes.count), at: 0)
            }
            bytes.insert(46, at: bytes.count - scale)
            while bytes.last == 48 { bytes.removeLast() }
            if bytes.last == 46 { bytes.removeLast() }
        }
        if negative { bytes.insert(45, at: 0) }
        return String(decoding: bytes, as: UTF8.self)
    }

    mutating func truncate() {
        if scale >= digits.count { digits = [0] } else { digits.removeLast(scale) }
        scale = 0
        trim()
    }

    mutating func percent() -> Bool {
        if scale >= 2 {
            scale -= 2
        } else {
            guard digits.count + 2 - scale <= Self.maximumDigits else { return false }
            digits.append(contentsOf: repeatElement(0, count: 2 - scale))
            scale = 0
        }
        return true
    }

    mutating func round(maximum: Int) {
        guard scale > maximum else { return }
        let removed = scale - maximum
        let retained = max(0, digits.count - removed)
        let roundUp =
            removed <= digits.count
            && (digits[retained] > 5
                || (digits[retained] == 5
                    && (digits[(retained + 1)...].contains(where: { $0 != 0 })
                        || (retained > 0 && digits[retained - 1] % 2 == 1))))
        digits = Array(digits.prefix(retained))
        if digits.isEmpty { digits = [0] }
        if roundUp { digits = Self.add(digits, [1]) }
        scale = maximum
        trim()
    }

    mutating func offset(_ delta: Int, subtract: Bool) -> Bool {
        if delta == 0 { return true }
        var other = String(delta.magnitude).utf8.map { $0 - 48 }
        guard other.count + scale <= Self.maximumDigits else { return false }
        other.append(contentsOf: repeatElement(0, count: scale))
        let otherNegative = (delta < 0) != subtract
        if negative == otherNegative {
            digits = Self.add(digits, other)
        } else {
            let less =
                digits.count != other.count ? digits.count < other.count : digits.lexicographicallyPrecedes(other)
            if less {
                digits = Self.subtract(other, digits)
                negative = otherNegative
            } else {
                digits = Self.subtract(digits, other)
            }
        }
        trim()
        return digits.count <= Self.maximumDigits
    }

    private static func add(_ left: [UInt8], _ right: [UInt8]) -> [UInt8] {
        var output: [UInt8] = []
        var carry: UInt8 = 0
        var l = left.count
        var r = right.count
        while l > 0 || r > 0 || carry > 0 {
            let a: UInt8 = l > 0 ? left[l - 1] : 0
            let b: UInt8 = r > 0 ? right[r - 1] : 0
            l = max(0, l - 1)
            r = max(0, r - 1)
            let sum = a + b + carry
            output.append(sum % 10)
            carry = sum / 10
        }
        return output.reversed()
    }

    private static func subtract(_ left: [UInt8], _ right: [UInt8]) -> [UInt8] {
        var output: [UInt8] = []
        var borrow = 0
        var r = right.count
        for digit in left.reversed() {
            let other = r > 0 ? Int(right[r - 1]) : 0
            r = max(0, r - 1)
            var difference = Int(digit) - other - borrow
            borrow = difference < 0 ? 1 : 0
            if difference < 0 { difference += 10 }
            output.append(UInt8(difference))
        }
        return output.reversed()
    }
}

func portableNumericOperand(_ input: String, function: String, minimum: Int, maximum: Int?) throws -> String {
    guard minimum >= 0, minimum <= 1000, maximum.map({ $0 >= minimum && $0 <= 1000 }) ?? true else {
        throw MF2Error.badOption("Fraction digits must be between zero and 1000, with minimum no greater than maximum.")
    }
    guard var decimal = DecimalOperand(input) else {
        throw MF2Error.badOperand("Numeric operand exceeds the supported decimal range.")
    }
    if function == "integer" { decimal.truncate() }
    if function == "percent", !decimal.percent() {
        throw MF2Error.badOperand("Numeric result exceeds the supported decimal range.")
    }
    if function == "number" || function == "percent", let maximum { decimal.round(maximum: maximum) }
    var formatted = decimal.canonical
    let fraction = formatted.split(separator: ".", omittingEmptySubsequences: false).dropFirst().first?.count ?? 0
    let padding = max(0, minimum - fraction)
    guard formatted.utf8.filter({ (48...57).contains($0) }).count + padding <= DecimalOperand.maximumDigits else {
        throw MF2Error.badOperand("Numeric result exceeds the supported decimal range.")
    }
    if padding > 0 {
        if fraction == 0 { formatted += "." }
        formatted += String(repeating: "0", count: padding)
    }
    return formatted
}
