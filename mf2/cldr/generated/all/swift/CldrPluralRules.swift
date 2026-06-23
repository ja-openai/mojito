// Generated from Unicode CLDR by mf2/cldr/update_generated.sh; do not edit by hand.
import Foundation

private let maxPluralOperandLength = 256
private let maxSafePluralOperandInteger = 9_007_199_254_740_991.0
private let maxSafePluralOperandInt: Int64 = 9_007_199_254_740_991
private let pluralOperandPattern = #"^[+-]?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$"#

struct NumberOperands {
    let n: Double
    let i: Int64
    let v: Int64
    let w: Int64
    let f: Int64
    let t: Int64
    let e: Int64
    let c: Int64

    init?(_ value: String) {
        guard value.count <= maxPluralOperandLength else { return nil }
        guard value.range(of: pluralOperandPattern, options: .regularExpression) != nil else { return nil }
        guard let parsed = Double(value), parsed.isFinite else { return nil }
        let n = abs(parsed)
        guard n <= maxSafePluralOperandInteger else { return nil }
        let normalized = value.trimmingCharacters(in: CharacterSet(charactersIn: "-+")).lowercased()
        let base = normalized.split(separator: "e", maxSplits: 1).first.map(String.init) ?? normalized
        let fraction = base.split(separator: ".", maxSplits: 1).dropFirst().first.map(String.init) ?? ""
        let fractionTrimmed = String(fraction.reversed().drop(while: { $0 == "0" }).reversed())
        self.n = n
        self.i = Int64(n.rounded(.towardZero))
        self.v = Int64(fraction.count)
        self.w = Int64(fractionTrimmed.count)
        guard let f = parsePluralInt(fraction), let t = parsePluralInt(fractionTrimmed) else { return nil }
        self.f = f
        self.t = t
        self.e = 0
        self.c = 0
    }

    fileprivate func operand(_ name: String) -> Int64 {
        switch name {
        case "i": i
        case "v": v
        case "w": w
        case "f": f
        case "t": t
        case "e": e
        case "c": c
        case "n": i
        default: 0
        }
    }

    fileprivate func operandDouble(_ name: String) -> Double {
        if name == "n" { return n }
        return Double(operand(name))
    }
}

private func parsePluralInt(_ value: String) -> Int64? {
    if value.isEmpty { return 0 }
    let digits = String(value.drop(while: { $0 == "0" }))
    let normalizedDigits = digits.isEmpty ? "0" : digits
    guard let parsed = Int64(normalizedDigits), parsed <= maxSafePluralOperandInt else {
        return nil
    }
    return parsed
}

func selectCardinal(locale: String, operands: NumberOperands) -> String {
    switch lookupRuleId(locales: cardinalLocales, parents: cardinalParents, locale: locale) {
    case "r0": selectCardinalR0(operands)
    case "r1": selectCardinalR1(operands)
    case "r2": selectCardinalR2(operands)
    case "r3": selectCardinalR3(operands)
    case "r4": selectCardinalR4(operands)
    case "r5": selectCardinalR5(operands)
    case "r6": selectCardinalR6(operands)
    case "r7": selectCardinalR7(operands)
    case "r8": selectCardinalR8(operands)
    case "r9": selectCardinalR9(operands)
    case "r10": selectCardinalR10(operands)
    case "r11": selectCardinalR11(operands)
    case "r12": selectCardinalR12(operands)
    case "r13": selectCardinalR13(operands)
    case "r14": selectCardinalR14(operands)
    case "r15": selectCardinalR15(operands)
    case "r16": selectCardinalR16(operands)
    case "r17": selectCardinalR17(operands)
    case "r18": selectCardinalR18(operands)
    case "r19": selectCardinalR19(operands)
    case "r20": selectCardinalR20(operands)
    case "r21": selectCardinalR21(operands)
    case "r22": selectCardinalR22(operands)
    case "r23": selectCardinalR23(operands)
    case "r24": selectCardinalR24(operands)
    case "r25": selectCardinalR25(operands)
    case "r26": selectCardinalR26(operands)
    case "r27": selectCardinalR27(operands)
    case "r28": selectCardinalR28(operands)
    case "r29": selectCardinalR29(operands)
    case "r30": selectCardinalR30(operands)
    case "r31": selectCardinalR31(operands)
    case "r32": selectCardinalR32(operands)
    case "r33": selectCardinalR33(operands)
    case "r34": selectCardinalR34(operands)
    case "r35": selectCardinalR35(operands)
    case "r36": selectCardinalR36(operands)
    case "r37": selectCardinalR37(operands)
    case "r38": selectCardinalR38(operands)
    case "r39": selectCardinalR39(operands)
    default: "other"
    }
}

func selectOrdinal(locale: String, operands: NumberOperands) -> String {
    switch lookupRuleId(locales: ordinalLocales, parents: ordinalParents, locale: locale) {
    case "r0": selectOrdinalR0(operands)
    case "r1": selectOrdinalR1(operands)
    case "r2": selectOrdinalR2(operands)
    case "r3": selectOrdinalR3(operands)
    case "r4": selectOrdinalR4(operands)
    case "r5": selectOrdinalR5(operands)
    case "r6": selectOrdinalR6(operands)
    case "r7": selectOrdinalR7(operands)
    case "r8": selectOrdinalR8(operands)
    case "r9": selectOrdinalR9(operands)
    case "r10": selectOrdinalR10(operands)
    case "r11": selectOrdinalR11(operands)
    case "r12": selectOrdinalR12(operands)
    case "r13": selectOrdinalR13(operands)
    case "r14": selectOrdinalR14(operands)
    case "r15": selectOrdinalR15(operands)
    case "r16": selectOrdinalR16(operands)
    case "r17": selectOrdinalR17(operands)
    case "r18": selectOrdinalR18(operands)
    case "r19": selectOrdinalR19(operands)
    case "r20": selectOrdinalR20(operands)
    case "r21": selectOrdinalR21(operands)
    case "r22": selectOrdinalR22(operands)
    case "r23": selectOrdinalR23(operands)
    case "r24": selectOrdinalR24(operands)
    default: "other"
    }
}

private let cardinalLocales: [String: String] = [
    "af": "r0",
    "ak": "r1",
    "am": "r2",
    "an": "r0",
    "ar": "r3",
    "ars": "r3",
    "as": "r2",
    "asa": "r0",
    "ast": "r4",
    "az": "r0",
    "bal": "r0",
    "be": "r5",
    "bem": "r0",
    "bez": "r0",
    "bg": "r0",
    "bho": "r1",
    "blo": "r6",
    "bm": "r7",
    "bn": "r2",
    "bo": "r7",
    "br": "r8",
    "brx": "r0",
    "bs": "r9",
    "ca": "r10",
    "ce": "r0",
    "ceb": "r11",
    "cgg": "r0",
    "chr": "r0",
    "ckb": "r0",
    "cs": "r12",
    "csw": "r1",
    "cv": "r6",
    "cy": "r13",
    "da": "r14",
    "de": "r4",
    "doi": "r2",
    "dsb": "r15",
    "dv": "r0",
    "dz": "r7",
    "ee": "r0",
    "el": "r0",
    "en": "r4",
    "eo": "r0",
    "es": "r16",
    "et": "r4",
    "eu": "r0",
    "fa": "r2",
    "ff": "r17",
    "fi": "r4",
    "fil": "r11",
    "fo": "r0",
    "fr": "r18",
    "fur": "r0",
    "fy": "r4",
    "ga": "r19",
    "gd": "r20",
    "gl": "r4",
    "gsw": "r0",
    "gu": "r2",
    "guw": "r1",
    "gv": "r21",
    "ha": "r0",
    "haw": "r0",
    "he": "r22",
    "hi": "r2",
    "hnj": "r7",
    "hr": "r9",
    "hsb": "r15",
    "hu": "r0",
    "hy": "r17",
    "ia": "r4",
    "id": "r7",
    "ie": "r4",
    "ig": "r7",
    "ii": "r7",
    "io": "r4",
    "is": "r23",
    "it": "r10",
    "iu": "r24",
    "ja": "r7",
    "jbo": "r7",
    "jgo": "r0",
    "jmc": "r0",
    "jv": "r7",
    "jw": "r7",
    "ka": "r0",
    "kab": "r17",
    "kaj": "r0",
    "kcg": "r0",
    "kde": "r7",
    "kea": "r7",
    "kk": "r0",
    "kkj": "r0",
    "kl": "r0",
    "km": "r7",
    "kn": "r2",
    "ko": "r7",
    "kok": "r2",
    "kok-Latn": "r2",
    "ks": "r0",
    "ksb": "r0",
    "ksh": "r6",
    "ku": "r0",
    "kw": "r25",
    "ky": "r0",
    "lag": "r26",
    "lb": "r0",
    "lg": "r0",
    "lij": "r4",
    "lkt": "r7",
    "lld": "r10",
    "ln": "r1",
    "lo": "r7",
    "lt": "r27",
    "lv": "r28",
    "mas": "r0",
    "mg": "r1",
    "mgo": "r0",
    "mk": "r29",
    "ml": "r0",
    "mn": "r0",
    "mo": "r30",
    "mr": "r0",
    "ms": "r7",
    "mt": "r31",
    "my": "r7",
    "nah": "r0",
    "naq": "r24",
    "nb": "r0",
    "nd": "r0",
    "ne": "r0",
    "nl": "r4",
    "nn": "r0",
    "nnh": "r0",
    "no": "r0",
    "nqo": "r7",
    "nr": "r0",
    "nso": "r1",
    "ny": "r0",
    "nyn": "r0",
    "om": "r0",
    "or": "r0",
    "os": "r0",
    "osa": "r7",
    "pa": "r1",
    "pap": "r0",
    "pcm": "r2",
    "pl": "r32",
    "prg": "r28",
    "ps": "r0",
    "pt": "r33",
    "pt-PT": "r10",
    "rm": "r0",
    "ro": "r30",
    "rof": "r0",
    "ru": "r34",
    "rwk": "r0",
    "sah": "r7",
    "saq": "r0",
    "sat": "r24",
    "sc": "r4",
    "scn": "r10",
    "sd": "r0",
    "sdh": "r0",
    "se": "r24",
    "seh": "r0",
    "ses": "r7",
    "sg": "r7",
    "sgs": "r35",
    "sh": "r9",
    "shi": "r36",
    "si": "r37",
    "sk": "r12",
    "sl": "r38",
    "sma": "r24",
    "smi": "r24",
    "smj": "r24",
    "smn": "r24",
    "sms": "r24",
    "sn": "r0",
    "so": "r0",
    "sq": "r0",
    "sr": "r9",
    "ss": "r0",
    "ssy": "r0",
    "st": "r0",
    "su": "r7",
    "sv": "r4",
    "sw": "r4",
    "syr": "r0",
    "ta": "r0",
    "te": "r0",
    "teo": "r0",
    "th": "r7",
    "ti": "r1",
    "tig": "r0",
    "tk": "r0",
    "tl": "r11",
    "tn": "r0",
    "to": "r7",
    "tpi": "r7",
    "tr": "r0",
    "ts": "r0",
    "tzm": "r39",
    "ug": "r0",
    "uk": "r34",
    "und": "r7",
    "ur": "r4",
    "uz": "r0",
    "ve": "r0",
    "vec": "r10",
    "vi": "r7",
    "vo": "r0",
    "vun": "r0",
    "wa": "r1",
    "wae": "r0",
    "wo": "r7",
    "xh": "r0",
    "xog": "r0",
    "yi": "r4",
    "yo": "r7",
    "yue": "r7",
    "zh": "r7",
    "zu": "r2",
]

private let ordinalLocales: [String: String] = [
    "af": "r0",
    "am": "r0",
    "an": "r0",
    "ar": "r0",
    "as": "r1",
    "ast": "r0",
    "az": "r2",
    "bal": "r3",
    "be": "r4",
    "bg": "r0",
    "blo": "r5",
    "bn": "r1",
    "bs": "r0",
    "ca": "r6",
    "ce": "r0",
    "cs": "r0",
    "cv": "r0",
    "cy": "r7",
    "da": "r0",
    "de": "r0",
    "dsb": "r0",
    "el": "r0",
    "en": "r8",
    "es": "r0",
    "et": "r0",
    "eu": "r0",
    "fa": "r0",
    "fi": "r0",
    "fil": "r3",
    "fr": "r3",
    "fy": "r0",
    "ga": "r3",
    "gd": "r9",
    "gl": "r0",
    "gsw": "r0",
    "gu": "r10",
    "he": "r0",
    "hi": "r10",
    "hr": "r0",
    "hsb": "r0",
    "hu": "r11",
    "hy": "r3",
    "ia": "r0",
    "id": "r0",
    "ie": "r0",
    "is": "r0",
    "it": "r12",
    "ja": "r0",
    "ka": "r13",
    "kk": "r14",
    "km": "r0",
    "kn": "r0",
    "ko": "r0",
    "kok": "r15",
    "kok-Latn": "r15",
    "kw": "r16",
    "ky": "r0",
    "lij": "r17",
    "lld": "r12",
    "lo": "r3",
    "lt": "r0",
    "lv": "r0",
    "mk": "r18",
    "ml": "r0",
    "mn": "r0",
    "mo": "r3",
    "mr": "r15",
    "ms": "r3",
    "my": "r0",
    "nb": "r0",
    "ne": "r19",
    "nl": "r0",
    "no": "r0",
    "or": "r20",
    "pa": "r0",
    "pl": "r0",
    "prg": "r0",
    "ps": "r0",
    "pt": "r0",
    "ro": "r3",
    "ru": "r0",
    "sc": "r12",
    "scn": "r17",
    "sd": "r0",
    "sh": "r0",
    "si": "r0",
    "sk": "r0",
    "sl": "r0",
    "sq": "r21",
    "sr": "r0",
    "sv": "r22",
    "sw": "r0",
    "ta": "r0",
    "te": "r0",
    "th": "r0",
    "tk": "r23",
    "tl": "r3",
    "tpi": "r0",
    "tr": "r0",
    "uk": "r24",
    "und": "r0",
    "ur": "r0",
    "uz": "r0",
    "vec": "r12",
    "vi": "r3",
    "yue": "r0",
    "zh": "r0",
    "zu": "r0",
]

private let cardinalParents: [String: String] = [:]

private let ordinalParents: [String: String] = [:]

private func lookupRuleId(locales: [String: String], parents: [String: String], locale: String) -> String? {
    for candidate in MF2LocaleKey.pluralLookupChain(locale, parents: parents) {
        if let rule = locales[candidate] { return rule }
    }
    return nil
}

private func selectCardinalR0(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) { return "one" }
    return "other"
}

private func selectCardinalR1(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) { return "one" }
    return "other"
}

private func selectCardinalR2(_ operands: NumberOperands) -> String {
    if (((0 <= operands.operand("i") && operands.operand("i") <= 0))) || (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) { return "one" }
    return "other"
}

private func selectCardinalR3(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0))) { return "zero" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) { return "one" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0))) { return "two" }
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 3.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 10.0))) { return "few" }
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 11.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 99.0))) { return "many" }
    return "other"
}

private func selectCardinalR4(_ operands: NumberOperands) -> String {
    if (((1 <= operands.operand("i") && operands.operand("i") <= 1)) && ((0 <= operands.operand("v") && operands.operand("v") <= 0))) { return "one" }
    return "other"
}

private func selectCardinalR5(_ operands: NumberOperands) -> String {
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 1.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 1.0)) && !(((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 11.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 11.0))) { return "one" }
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 2.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 4.0)) && !(((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 12.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 14.0))) { return "few" }
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 0.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 0.0))) || ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 5.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 9.0))) || ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 11.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 14.0))) { return "many" }
    return "other"
}

private func selectCardinalR6(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0))) { return "zero" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) { return "one" }
    return "other"
}

private func selectCardinalR7(_ _: NumberOperands) -> String {
    return "other"
}

private func selectCardinalR8(_ operands: NumberOperands) -> String {
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 1.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 1.0)) && !(((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 11.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 11.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 71.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 71.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 91.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 91.0))) { return "one" }
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 2.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 2.0)) && !(((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 12.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 12.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 72.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 72.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 92.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 92.0))) { return "two" }
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 3.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 4.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 9.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 9.0)) && !(((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 10.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 19.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 70.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 79.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 90.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 99.0))) { return "few" }
    if (!((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0)) && (((operands.operandDouble("n").truncatingRemainder(dividingBy: 1000000.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 1000000.0)) && 0.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 1000000.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 1000000.0)) <= 0.0))) { return "many" }
    return "other"
}

private func selectCardinalR9(_ operands: NumberOperands) -> String {
    if (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((1 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 1)) && !((11 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 11))) || (((1 <= (operands.operand("f") % 10) && (operands.operand("f") % 10) <= 1)) && !((11 <= (operands.operand("f") % 100) && (operands.operand("f") % 100) <= 11))) { return "one" }
    if (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((2 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 4)) && !((12 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 14))) || (((2 <= (operands.operand("f") % 10) && (operands.operand("f") % 10) <= 4)) && !((12 <= (operands.operand("f") % 100) && (operands.operand("f") % 100) <= 14))) { return "few" }
    return "other"
}

private func selectCardinalR10(_ operands: NumberOperands) -> String {
    if (((1 <= operands.operand("i") && operands.operand("i") <= 1)) && ((0 <= operands.operand("v") && operands.operand("v") <= 0))) { return "one" }
    if (((0 <= operands.operand("e") && operands.operand("e") <= 0)) && !((0 <= operands.operand("i") && operands.operand("i") <= 0)) && ((0 <= (operands.operand("i") % 1000000) && (operands.operand("i") % 1000000) <= 0)) && ((0 <= operands.operand("v") && operands.operand("v") <= 0))) || (!((0 <= operands.operand("e") && operands.operand("e") <= 5))) { return "many" }
    return "other"
}

private func selectCardinalR11(_ operands: NumberOperands) -> String {
    if (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((1 <= operands.operand("i") && operands.operand("i") <= 1) || (2 <= operands.operand("i") && operands.operand("i") <= 2) || (3 <= operands.operand("i") && operands.operand("i") <= 3))) || (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && !((4 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 4) || (6 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 6) || (9 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 9))) || (!((0 <= operands.operand("v") && operands.operand("v") <= 0)) && !((4 <= (operands.operand("f") % 10) && (operands.operand("f") % 10) <= 4) || (6 <= (operands.operand("f") % 10) && (operands.operand("f") % 10) <= 6) || (9 <= (operands.operand("f") % 10) && (operands.operand("f") % 10) <= 9))) { return "one" }
    return "other"
}

private func selectCardinalR12(_ operands: NumberOperands) -> String {
    if (((1 <= operands.operand("i") && operands.operand("i") <= 1)) && ((0 <= operands.operand("v") && operands.operand("v") <= 0))) { return "one" }
    if (((2 <= operands.operand("i") && operands.operand("i") <= 4)) && ((0 <= operands.operand("v") && operands.operand("v") <= 0))) { return "few" }
    if (!((0 <= operands.operand("v") && operands.operand("v") <= 0))) { return "many" }
    return "other"
}

private func selectCardinalR13(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0))) { return "zero" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) { return "one" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0))) { return "two" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0))) { return "few" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 6.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 6.0))) { return "many" }
    return "other"
}

private func selectCardinalR14(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) || (!((0 <= operands.operand("t") && operands.operand("t") <= 0)) && ((0 <= operands.operand("i") && operands.operand("i") <= 0) || (1 <= operands.operand("i") && operands.operand("i") <= 1))) { return "one" }
    return "other"
}

private func selectCardinalR15(_ operands: NumberOperands) -> String {
    if (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((1 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 1))) || (((1 <= (operands.operand("f") % 100) && (operands.operand("f") % 100) <= 1))) { return "one" }
    if (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((2 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 2))) || (((2 <= (operands.operand("f") % 100) && (operands.operand("f") % 100) <= 2))) { return "two" }
    if (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((3 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 4))) || (((3 <= (operands.operand("f") % 100) && (operands.operand("f") % 100) <= 4))) { return "few" }
    return "other"
}

private func selectCardinalR16(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) { return "one" }
    if (((0 <= operands.operand("e") && operands.operand("e") <= 0)) && !((0 <= operands.operand("i") && operands.operand("i") <= 0)) && ((0 <= (operands.operand("i") % 1000000) && (operands.operand("i") % 1000000) <= 0)) && ((0 <= operands.operand("v") && operands.operand("v") <= 0))) || (!((0 <= operands.operand("e") && operands.operand("e") <= 5))) { return "many" }
    return "other"
}

private func selectCardinalR17(_ operands: NumberOperands) -> String {
    if (((0 <= operands.operand("i") && operands.operand("i") <= 0) || (1 <= operands.operand("i") && operands.operand("i") <= 1))) { return "one" }
    return "other"
}

private func selectCardinalR18(_ operands: NumberOperands) -> String {
    if (((0 <= operands.operand("i") && operands.operand("i") <= 0) || (1 <= operands.operand("i") && operands.operand("i") <= 1))) { return "one" }
    if (((0 <= operands.operand("e") && operands.operand("e") <= 0)) && !((0 <= operands.operand("i") && operands.operand("i") <= 0)) && ((0 <= (operands.operand("i") % 1000000) && (operands.operand("i") % 1000000) <= 0)) && ((0 <= operands.operand("v") && operands.operand("v") <= 0))) || (!((0 <= operands.operand("e") && operands.operand("e") <= 5))) { return "many" }
    return "other"
}

private func selectCardinalR19(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) { return "one" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0))) { return "two" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 6.0))) { return "few" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 7.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 10.0))) { return "many" }
    return "other"
}

private func selectCardinalR20(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 11.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 11.0))) { return "one" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 12.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 12.0))) { return "two" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 10.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 13.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 19.0))) { return "few" }
    return "other"
}

private func selectCardinalR21(_ operands: NumberOperands) -> String {
    if (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((1 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 1))) { return "one" }
    if (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((2 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 2))) { return "two" }
    if (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((0 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 0) || (20 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 20) || (40 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 40) || (60 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 60) || (80 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 80))) { return "few" }
    if (!((0 <= operands.operand("v") && operands.operand("v") <= 0))) { return "many" }
    return "other"
}

private func selectCardinalR22(_ operands: NumberOperands) -> String {
    if (((1 <= operands.operand("i") && operands.operand("i") <= 1)) && ((0 <= operands.operand("v") && operands.operand("v") <= 0))) || (((0 <= operands.operand("i") && operands.operand("i") <= 0)) && !((0 <= operands.operand("v") && operands.operand("v") <= 0))) { return "one" }
    if (((2 <= operands.operand("i") && operands.operand("i") <= 2)) && ((0 <= operands.operand("v") && operands.operand("v") <= 0))) { return "two" }
    return "other"
}

private func selectCardinalR23(_ operands: NumberOperands) -> String {
    if (((0 <= operands.operand("t") && operands.operand("t") <= 0)) && ((1 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 1)) && !((11 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 11))) || (((1 <= (operands.operand("t") % 10) && (operands.operand("t") % 10) <= 1)) && !((11 <= (operands.operand("t") % 100) && (operands.operand("t") % 100) <= 11))) { return "one" }
    return "other"
}

private func selectCardinalR24(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) { return "one" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0))) { return "two" }
    return "other"
}

private func selectCardinalR25(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0))) { return "zero" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) { return "one" }
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 2.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 2.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 22.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 22.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 42.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 42.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 62.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 62.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 82.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 82.0))) || ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 1000.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 1000.0)) && 0.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 1000.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 1000.0)) <= 0.0)) && (((operands.operandDouble("n").truncatingRemainder(dividingBy: 100000.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100000.0)) && 1000.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100000.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100000.0)) <= 20000.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100000.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100000.0)) && 40000.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100000.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100000.0)) <= 40000.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100000.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100000.0)) && 60000.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100000.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100000.0)) <= 60000.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100000.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100000.0)) && 80000.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100000.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100000.0)) <= 80000.0))) || (!((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0)) && (((operands.operandDouble("n").truncatingRemainder(dividingBy: 1000000.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 1000000.0)) && 100000.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 1000000.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 1000000.0)) <= 100000.0))) { return "two" }
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 3.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 3.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 23.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 23.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 43.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 43.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 63.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 63.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 83.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 83.0))) { return "few" }
    if (!((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0)) && (((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 1.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 1.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 21.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 21.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 41.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 41.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 61.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 61.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 81.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 81.0))) { return "many" }
    return "other"
}

private func selectCardinalR26(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0))) { return "zero" }
    if (((0 <= operands.operand("i") && operands.operand("i") <= 0) || (1 <= operands.operand("i") && operands.operand("i") <= 1)) && !((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0))) { return "one" }
    return "other"
}

private func selectCardinalR27(_ operands: NumberOperands) -> String {
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 1.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 1.0)) && !(((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 11.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 19.0))) { return "one" }
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 2.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 9.0)) && !(((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 11.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 19.0))) { return "few" }
    if (!((0 <= operands.operand("f") && operands.operand("f") <= 0))) { return "many" }
    return "other"
}

private func selectCardinalR28(_ operands: NumberOperands) -> String {
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 0.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 0.0))) || ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 11.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 19.0))) || (((2 <= operands.operand("v") && operands.operand("v") <= 2)) && ((11 <= (operands.operand("f") % 100) && (operands.operand("f") % 100) <= 19))) { return "zero" }
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 1.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 1.0)) && !(((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 11.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 11.0))) || (((2 <= operands.operand("v") && operands.operand("v") <= 2)) && ((1 <= (operands.operand("f") % 10) && (operands.operand("f") % 10) <= 1)) && !((11 <= (operands.operand("f") % 100) && (operands.operand("f") % 100) <= 11))) || (!((2 <= operands.operand("v") && operands.operand("v") <= 2)) && ((1 <= (operands.operand("f") % 10) && (operands.operand("f") % 10) <= 1))) { return "one" }
    return "other"
}

private func selectCardinalR29(_ operands: NumberOperands) -> String {
    if (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((1 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 1)) && !((11 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 11))) || (((1 <= (operands.operand("f") % 10) && (operands.operand("f") % 10) <= 1)) && !((11 <= (operands.operand("f") % 100) && (operands.operand("f") % 100) <= 11))) { return "one" }
    return "other"
}

private func selectCardinalR30(_ operands: NumberOperands) -> String {
    if (((1 <= operands.operand("i") && operands.operand("i") <= 1)) && ((0 <= operands.operand("v") && operands.operand("v") <= 0))) { return "one" }
    if (!((0 <= operands.operand("v") && operands.operand("v") <= 0))) || (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0))) || (!((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0)) && (((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 1.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 19.0))) { return "few" }
    return "other"
}

private func selectCardinalR31(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) { return "one" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0))) { return "two" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0))) || ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 3.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 10.0))) { return "few" }
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 11.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 19.0))) { return "many" }
    return "other"
}

private func selectCardinalR32(_ operands: NumberOperands) -> String {
    if (((1 <= operands.operand("i") && operands.operand("i") <= 1)) && ((0 <= operands.operand("v") && operands.operand("v") <= 0))) { return "one" }
    if (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((2 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 4)) && !((12 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 14))) { return "few" }
    if (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && !((1 <= operands.operand("i") && operands.operand("i") <= 1)) && ((0 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 1))) || (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((5 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 9))) || (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((12 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 14))) { return "many" }
    return "other"
}

private func selectCardinalR33(_ operands: NumberOperands) -> String {
    if (((0 <= operands.operand("i") && operands.operand("i") <= 1))) { return "one" }
    if (((0 <= operands.operand("e") && operands.operand("e") <= 0)) && !((0 <= operands.operand("i") && operands.operand("i") <= 0)) && ((0 <= (operands.operand("i") % 1000000) && (operands.operand("i") % 1000000) <= 0)) && ((0 <= operands.operand("v") && operands.operand("v") <= 0))) || (!((0 <= operands.operand("e") && operands.operand("e") <= 5))) { return "many" }
    return "other"
}

private func selectCardinalR34(_ operands: NumberOperands) -> String {
    if (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((1 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 1)) && !((11 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 11))) { return "one" }
    if (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((2 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 4)) && !((12 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 14))) { return "few" }
    if (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((0 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 0))) || (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((5 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 9))) || (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((11 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 14))) { return "many" }
    return "other"
}

private func selectCardinalR35(_ operands: NumberOperands) -> String {
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 1.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 1.0)) && !(((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 11.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 11.0))) { return "one" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0))) { return "two" }
    if (!((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0)) && (((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 2.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 9.0)) && !(((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 11.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 19.0))) { return "few" }
    if (!((0 <= operands.operand("f") && operands.operand("f") <= 0))) { return "many" }
    return "other"
}

private func selectCardinalR36(_ operands: NumberOperands) -> String {
    if (((0 <= operands.operand("i") && operands.operand("i") <= 0))) || (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) { return "one" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 10.0))) { return "few" }
    return "other"
}

private func selectCardinalR37(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) || (((0 <= operands.operand("i") && operands.operand("i") <= 0)) && ((1 <= operands.operand("f") && operands.operand("f") <= 1))) { return "one" }
    return "other"
}

private func selectCardinalR38(_ operands: NumberOperands) -> String {
    if (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((1 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 1))) { return "one" }
    if (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((2 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 2))) { return "two" }
    if (((0 <= operands.operand("v") && operands.operand("v") <= 0)) && ((3 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 4))) || (!((0 <= operands.operand("v") && operands.operand("v") <= 0))) { return "few" }
    return "other"
}

private func selectCardinalR39(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) || (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 11.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 99.0))) { return "one" }
    return "other"
}

private func selectOrdinalR0(_ _: NumberOperands) -> String {
    return "other"
}

private func selectOrdinalR1(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 5.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 5.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 7.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 7.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 8.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 8.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 9.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 9.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 10.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 10.0))) { return "one" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0))) { return "two" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 4.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0))) { return "few" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 6.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 6.0))) { return "many" }
    return "other"
}

private func selectOrdinalR2(_ operands: NumberOperands) -> String {
    if (((1 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 1) || (2 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 2) || (5 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 5) || (7 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 7) || (8 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 8))) || (((20 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 20) || (50 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 50) || (70 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 70) || (80 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 80))) { return "one" }
    if (((3 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 3) || (4 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 4))) || (((100 <= (operands.operand("i") % 1000) && (operands.operand("i") % 1000) <= 100) || (200 <= (operands.operand("i") % 1000) && (operands.operand("i") % 1000) <= 200) || (300 <= (operands.operand("i") % 1000) && (operands.operand("i") % 1000) <= 300) || (400 <= (operands.operand("i") % 1000) && (operands.operand("i") % 1000) <= 400) || (500 <= (operands.operand("i") % 1000) && (operands.operand("i") % 1000) <= 500) || (600 <= (operands.operand("i") % 1000) && (operands.operand("i") % 1000) <= 600) || (700 <= (operands.operand("i") % 1000) && (operands.operand("i") % 1000) <= 700) || (800 <= (operands.operand("i") % 1000) && (operands.operand("i") % 1000) <= 800) || (900 <= (operands.operand("i") % 1000) && (operands.operand("i") % 1000) <= 900))) { return "few" }
    if (((0 <= operands.operand("i") && operands.operand("i") <= 0))) || (((6 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 6))) || (((40 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 40) || (60 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 60) || (90 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 90))) { return "many" }
    return "other"
}

private func selectOrdinalR3(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) { return "one" }
    return "other"
}

private func selectOrdinalR4(_ operands: NumberOperands) -> String {
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 2.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 2.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 3.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 3.0)) && !(((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 12.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 12.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 13.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 13.0))) { return "few" }
    return "other"
}

private func selectOrdinalR5(_ operands: NumberOperands) -> String {
    if (((0 <= operands.operand("i") && operands.operand("i") <= 0))) { return "zero" }
    if (((1 <= operands.operand("i") && operands.operand("i") <= 1))) { return "one" }
    if (((2 <= operands.operand("i") && operands.operand("i") <= 2) || (3 <= operands.operand("i") && operands.operand("i") <= 3) || (4 <= operands.operand("i") && operands.operand("i") <= 4) || (5 <= operands.operand("i") && operands.operand("i") <= 5) || (6 <= operands.operand("i") && operands.operand("i") <= 6))) { return "few" }
    return "other"
}

private func selectOrdinalR6(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0))) { return "one" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0))) { return "two" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 4.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0))) { return "few" }
    return "other"
}

private func selectOrdinalR7(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 7.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 7.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 8.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 8.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 9.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 9.0))) { return "zero" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) { return "one" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0))) { return "two" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 4.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0))) { return "few" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 5.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 5.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 6.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 6.0))) { return "many" }
    return "other"
}

private func selectOrdinalR8(_ operands: NumberOperands) -> String {
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 1.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 1.0)) && !(((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 11.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 11.0))) { return "one" }
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 2.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 2.0)) && !(((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 12.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 12.0))) { return "two" }
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 3.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 3.0)) && !(((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 13.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 13.0))) { return "few" }
    return "other"
}

private func selectOrdinalR9(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 11.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 11.0))) { return "one" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 12.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 12.0))) { return "two" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 13.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 13.0))) { return "few" }
    return "other"
}

private func selectOrdinalR10(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) { return "one" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0))) { return "two" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 4.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0))) { return "few" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 6.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 6.0))) { return "many" }
    return "other"
}

private func selectOrdinalR11(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 5.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 5.0))) { return "one" }
    return "other"
}

private func selectOrdinalR12(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 11.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 11.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 8.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 8.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 80.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 80.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 800.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 800.0))) { return "many" }
    return "other"
}

private func selectOrdinalR13(_ operands: NumberOperands) -> String {
    if (((1 <= operands.operand("i") && operands.operand("i") <= 1))) { return "one" }
    if (((0 <= operands.operand("i") && operands.operand("i") <= 0))) || (((2 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 20) || (40 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 40) || (60 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 60) || (80 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 80))) { return "many" }
    return "other"
}

private func selectOrdinalR14(_ operands: NumberOperands) -> String {
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 6.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 6.0))) || ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 9.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 9.0))) || ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 0.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 0.0)) && !((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0))) { return "many" }
    return "other"
}

private func selectOrdinalR15(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) { return "one" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0))) { return "two" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 4.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0))) { return "few" }
    return "other"
}

private func selectOrdinalR16(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0))) || ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 1.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 4.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 21.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 24.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 41.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 44.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 61.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 64.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 81.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 84.0))) { return "one" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 5.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 5.0))) || ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 5.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 5.0))) { return "many" }
    return "other"
}

private func selectOrdinalR17(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 11.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 11.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 8.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 8.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 80.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 89.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 800.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 899.0))) { return "many" }
    return "other"
}

private func selectOrdinalR18(_ operands: NumberOperands) -> String {
    if (((1 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 1)) && !((11 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 11))) { return "one" }
    if (((2 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 2)) && !((12 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 12))) { return "two" }
    if (((7 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 7) || (8 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 8)) && !((17 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 17) || (18 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 18))) { return "many" }
    return "other"
}

private func selectOrdinalR19(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0))) { return "one" }
    return "other"
}

private func selectOrdinalR20(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 5.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 5.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 7.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 9.0))) { return "one" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) || (operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0))) { return "two" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 4.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0))) { return "few" }
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 6.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 6.0))) { return "many" }
    return "other"
}

private func selectOrdinalR21(_ operands: NumberOperands) -> String {
    if (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0))) { return "one" }
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 4.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 4.0)) && !(((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 14.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 14.0))) { return "many" }
    return "other"
}

private func selectOrdinalR22(_ operands: NumberOperands) -> String {
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 1.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 1.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 2.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 2.0)) && !(((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 11.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 11.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 12.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 12.0))) { return "one" }
    return "other"
}

private func selectOrdinalR23(_ operands: NumberOperands) -> String {
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 6.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 6.0) || ((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 9.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 9.0))) || (((operands.operandDouble("n").rounded(.towardZero) == operands.operandDouble("n") && 10.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 10.0))) { return "few" }
    return "other"
}

private func selectOrdinalR24(_ operands: NumberOperands) -> String {
    if ((((operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && 3.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 10.0)) <= 3.0)) && !(((operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)).rounded(.towardZero) == (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && 13.0 <= (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) && (operands.operandDouble("n").truncatingRemainder(dividingBy: 100.0)) <= 13.0))) { return "few" }
    return "other"
}
