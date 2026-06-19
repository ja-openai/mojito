import Foundation

struct ProbeCase {
    let id: String
    let language: String
    let text: String
    let rangeText: String
    let morphology: Morphology?
    let automatic: Bool
}

func morphology(
    gender: Morphology.GrammaticalGender? = nil,
    number: Morphology.GrammaticalNumber? = nil,
    partOfSpeech: Morphology.PartOfSpeech? = nil,
    grammaticalCase: Morphology.GrammaticalCase? = nil,
    definiteness: Morphology.Definiteness? = nil
) -> Morphology {
    var value = Morphology()
    value.grammaticalGender = gender
    value.number = number
    value.partOfSpeech = partOfSpeech
    if #available(macOS 14.0, *) {
        value.grammaticalCase = grammaticalCase
        value.definiteness = definiteness
    }
    return value
}

func inflect(_ probe: ProbeCase) -> String {
    let attributed = NSMutableAttributedString(string: probe.text)
    let fullRange = NSRange(location: 0, length: attributed.length)
    attributed.addAttribute(.languageIdentifier, value: probe.language, range: fullRange)
    guard let range = probe.text.range(of: probe.rangeText) else {
        return "ERROR: missing range"
    }
    let nsRange = NSRange(range, in: probe.text)
    let rule: InflectionRule
    if probe.automatic {
        rule = .automatic
    } else if let morphology = probe.morphology {
        rule = InflectionRule(morphology: morphology)
    } else {
        return "ERROR: missing morphology"
    }
    attributed.addAttribute(.inflectionRule, value: rule, range: nsRange)
    if let morphology = probe.morphology {
        attributed.addAttribute(.morphology, value: morphology, range: nsRange)
    }
    return attributed.inflecting().string
}

let cases: [ProbeCase] = [
    ProbeCase(
        id: "fr-explicit-feminine-sword",
        language: "fr",
        text: "Vous avez ramassé épée.",
        rangeText: "épée",
        morphology: morphology(gender: .feminine, number: .singular, partOfSpeech: .noun),
        automatic: false
    ),
    ProbeCase(
        id: "fr-explicit-masculine-shield",
        language: "fr",
        text: "Vous avez ramassé bouclier.",
        rangeText: "bouclier",
        morphology: morphology(gender: .masculine, number: .singular, partOfSpeech: .noun),
        automatic: false
    ),
    ProbeCase(
        id: "fr-auto-sword",
        language: "fr",
        text: "Vous avez ramassé épée.",
        rangeText: "épée",
        morphology: nil,
        automatic: true
    ),
    ProbeCase(
        id: "fr-auto-aspired-h",
        language: "fr",
        text: "Vous avez ramassé héros.",
        rangeText: "héros",
        morphology: nil,
        automatic: true
    ),
    ProbeCase(
        id: "fr-auto-silent-h",
        language: "fr",
        text: "Vous avez réservé hôtel.",
        rangeText: "hôtel",
        morphology: nil,
        automatic: true
    ),
    ProbeCase(
        id: "de-explicit-accusative-shield",
        language: "de",
        text: "Du hast Schild aufgehoben.",
        rangeText: "Schild",
        morphology: morphology(
            gender: .masculine,
            number: .singular,
            partOfSpeech: .noun,
            grammaticalCase: .accusative,
            definiteness: .definite
        ),
        automatic: false
    ),
    ProbeCase(
        id: "de-auto-shield",
        language: "de",
        text: "Du hast Schild aufgehoben.",
        rangeText: "Schild",
        morphology: nil,
        automatic: true
    ),
]

let iterations = CommandLine.arguments.dropFirst().compactMap { Int($0) }.first ?? 10_000

func jsonEscape(_ value: String) -> String {
    value
        .replacingOccurrences(of: "\\", with: "\\\\")
        .replacingOccurrences(of: "\"", with: "\\\"")
        .replacingOccurrences(of: "\n", with: "\\n")
}

func time(_ block: () -> Void) -> Double {
    let start = DispatchTime.now().uptimeNanoseconds
    block()
    let end = DispatchTime.now().uptimeNanoseconds
    return Double(end - start) / 1_000_000.0
}

print("{")
print("  \"canInflect\": {")
let langs = ["fr", "de", "ru", "ar", "ja", "cy"]
for (index, language) in langs.enumerated() {
    let suffix = index == langs.count - 1 ? "" : ","
    print("    \"\(language)\": \(InflectionRule.canInflect(language: language))\(suffix)")
}
print("  },")
print("  \"cases\": [")
for (index, probe) in cases.enumerated() {
    let output = jsonEscape(inflect(probe))
    let suffix = index == cases.count - 1 ? "" : ","
    print("    {\"id\": \"\(probe.id)\", \"language\": \"\(probe.language)\", \"input\": \"\(jsonEscape(probe.text))\", \"output\": \"\(output)\"}\(suffix)")
}
print("  ],")
let perfProbe = cases.first { $0.id == "de-explicit-accusative-shield" }!
var checksum = 0
let elapsedMs = time {
    for _ in 0..<iterations {
        checksum += inflect(perfProbe).count
    }
}
print("  \"perf\": {")
print("    \"caseId\": \"\(perfProbe.id)\",")
print("    \"iterations\": \(iterations),")
print("    \"elapsedMs\": \(String(format: "%.3f", elapsedMs)),")
print("    \"perOpUs\": \(String(format: "%.3f", elapsedMs * 1000.0 / Double(iterations))),")
print("    \"checksum\": \(checksum)")
print("  }")
print("}")
