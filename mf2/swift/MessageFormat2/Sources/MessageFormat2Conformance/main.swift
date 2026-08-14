import Foundation
import MessageFormat2

do {
    let arguments = Array(CommandLine.arguments.dropFirst())
    if arguments.first == "--bench" {
        try runBenchmark(arguments: Array(arguments.dropFirst()))
        Foundation.exit(0)
    }
    if arguments.first == "--bench-parse" {
        try runParseBenchmark(arguments: Array(arguments.dropFirst()))
        Foundation.exit(0)
    }

    let fixtureDirectory = try resolveFixtureDirectory(arguments: arguments)
    var checkedSourceCases = 0
    var checkedCases = 0
    var checkedPartsCases = 0
    var checkedFallbackCases = 0
    var checkedFallbackPartsCases = 0

    for fixtureURL in try fixtureURLs(in: fixtureDirectory) {
        let fixture = try JSONDecoder().decode(
            SourceToModelFixture.self,
            from: Data(contentsOf: fixtureURL)
        )

        let parsed = parseToModel(fixture.source)
        if parsed.model != fixture.expectedModel {
            throw ConformanceError.parseMismatch(
                fixture: fixtureURL.lastPathComponent,
                expected: "\(fixture.expectedModel)",
                actual: "\(String(describing: parsed.model)); diagnostics=\(parsed.diagnostics)"
            )
        }
        checkedSourceCases += 1

        for formatCase in fixture.formatCases {
            let actual = try formatMessage(
                fixture.expectedModel,
                arguments: formatCase.arguments,
                locale: formatCase.locale,
                bidiIsolation: formatCase.bidiIsolation ?? .none
            )
            if actual.hasErrors {
                throw ConformanceError.expectedNoFormatErrors(
                    fixture: fixtureURL.lastPathComponent,
                    actual: actual.errors.map(\.code)
                )
            }
            if actual.value != formatCase.expected {
                throw ConformanceError.formatMismatch(
                    fixture: fixtureURL.lastPathComponent,
                    expected: formatCase.expected,
                    actual: actual.value
                )
            }
            checkedCases += 1
        }
        for partsCase in fixture.partsCases {
            let actual = try formatMessageToParts(
                fixture.expectedModel,
                arguments: partsCase.arguments,
                locale: partsCase.locale
            )
            if actual.hasErrors {
                throw ConformanceError.expectedNoFormatErrors(
                    fixture: fixtureURL.lastPathComponent,
                    actual: actual.errors.map(\.code)
                )
            }
            if actual.parts != partsCase.expected {
                throw ConformanceError.partsMismatch(
                    fixture: fixtureURL.lastPathComponent,
                    expected: "\(partsCase.expected)",
                    actual: "\(actual.parts)"
                )
            }
            checkedPartsCases += 1
        }
        for fallbackCase in fixture.fallbackCases {
            let actual = try formatMessage(
                fixture.expectedModel,
                arguments: fallbackCase.arguments,
                locale: fallbackCase.locale,
                bidiIsolation: fallbackCase.bidiIsolation ?? .none
            )
            if actual.value != fallbackCase.expected {
                throw ConformanceError.formatMismatch(
                    fixture: fixtureURL.lastPathComponent,
                    expected: fallbackCase.expected,
                    actual: actual.value
                )
            }
            try assertErrorCodes(
                fixture: fixtureURL.lastPathComponent,
                label: "fallback errors",
                actual: actual.errors,
                expected: fallbackCase.expectedErrors
            )
            checkedFallbackCases += 1
        }
        for partsCase in fixture.fallbackPartsCases {
            let actual = try formatMessageToParts(
                fixture.expectedModel,
                arguments: partsCase.arguments,
                locale: partsCase.locale
            )
            if actual.parts != partsCase.expected {
                throw ConformanceError.partsMismatch(
                    fixture: fixtureURL.lastPathComponent,
                    expected: "\(partsCase.expected)",
                    actual: "\(actual.parts)"
                )
            }
            try assertErrorCodes(
                fixture: fixtureURL.lastPathComponent,
                label: "fallback parts errors",
                actual: actual.errors,
                expected: partsCase.expectedErrors
            )
            checkedFallbackPartsCases += 1
        }
    }

    let checkedErrorCases = try runFormatErrorFixtures(
        fixtureRoot: fixtureDirectory.deletingLastPathComponent()
    )
    let checkedInvalidSourceCases = try runInvalidSourceFixtures(
        fixtureRoot: fixtureDirectory.deletingLastPathComponent()
    )
    let checkedLocaleKeyCases = try runLocaleKeyFixtures(
        fixtureRoot: fixtureDirectory.deletingLastPathComponent()
    )
    try checkPublicApiBoundary()
    try runPublicApiEdgeChecks()

    print(
        "Swift MF2 conformance runner passed \(checkedSourceCases) source models, \(checkedCases) format cases, \(checkedPartsCases) parts cases, \(checkedFallbackCases) fallback cases, \(checkedFallbackPartsCases) fallback parts cases, \(checkedInvalidSourceCases) invalid source cases, \(checkedErrorCases) format error cases, and \(checkedLocaleKeyCases) locale key cases."
    )
} catch {
    fputs("Swift MF2 conformance runner failed: \(error)\n", stderr)
    Foundation.exit(1)
}

private func resolveFixtureDirectory(arguments: [String]) throws -> URL {
    if let first = arguments.first {
        return URL(fileURLWithPath: first).standardizedFileURL
    }
    return URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        .appendingPathComponent("../../conformance/fixtures/source-to-model")
        .standardizedFileURL
}

private func runBenchmark(arguments: [String]) throws {
    let fixtureDirectory = try resolveFixtureDirectory(arguments: arguments)
    let iterations = Int(arguments.dropFirst().first ?? "100000") ?? 100000
    let warmupIterations = Int(arguments.dropFirst(2).first ?? "10000") ?? 10000
    let cases = try fixtureURLs(in: fixtureDirectory).flatMap { fixtureURL in
        let fixture = try JSONDecoder().decode(
            SourceToModelFixture.self,
            from: Data(contentsOf: fixtureURL)
        )
        return fixture.formatCases.map { formatCase in
            BenchCase(
                model: fixture.expectedModel,
                locale: formatCase.locale,
                arguments: formatCase.arguments
            )
        }
    }

    guard !cases.isEmpty else {
        throw ConformanceError.noFormatCases
    }

    for index in 0..<warmupIterations {
        let benchCase = cases[index % cases.count]
        let result = try benchCase.model.format(
            arguments: benchCase.arguments,
            locale: benchCase.locale
        )
        if result.hasErrors { throw ConformanceError.expectedNoFormatErrors(fixture: "bench", actual: result.errors.map(\.code)) }
    }

    let started = Date()
    var bytes = 0
    for index in 0..<iterations {
        let benchCase = cases[index % cases.count]
        let output = try benchCase.model.format(
            arguments: benchCase.arguments,
            locale: benchCase.locale
        )
        if output.hasErrors { throw ConformanceError.expectedNoFormatErrors(fixture: "bench", actual: output.errors.map(\.code)) }
        bytes += output.value.utf8.count
    }
    let seconds = Date().timeIntervalSince(started)
    let opsPerSecond = Double(iterations) / seconds
    print(
        "swift format iterations=\(iterations) warmup=\(warmupIterations) cases=\(cases.count) seconds=\(String(format: "%.6f", seconds)) ops_per_second=\(String(format: "%.0f", opsPerSecond)) bytes=\(bytes)"
    )
}

private func runParseBenchmark(arguments: [String]) throws {
    let fixtureDirectory = try resolveFixtureDirectory(arguments: arguments)
    let iterations = Int(arguments.dropFirst().first ?? "100000") ?? 100000
    let warmupIterations = Int(arguments.dropFirst(2).first ?? "10000") ?? 10000
    let sources = try fixtureURLs(in: fixtureDirectory).compactMap { fixtureURL in
        let fixture = try JSONDecoder().decode(
            SourceOnlyFixture.self,
            from: Data(contentsOf: fixtureURL)
        )
        return fixture.source
    }

    guard !sources.isEmpty else {
        throw ConformanceError.noSourceCases
    }

    for index in 0..<warmupIterations {
        _ = parseToModel(sources[index % sources.count])
    }

    let started = Date()
    var bytes = 0
    var parsedCount = 0
    var diagnosticCount = 0
    for index in 0..<iterations {
        let source = sources[index % sources.count]
        let result = parseToModel(source)
        if result.model != nil {
            parsedCount += 1
        }
        diagnosticCount += result.diagnostics.count
        bytes += source.data(using: .utf8)?.count ?? 0
    }
    let seconds = Date().timeIntervalSince(started)
    let opsPerSecond = Double(iterations) / seconds
    print(
        "swift parse iterations=\(iterations) warmup=\(warmupIterations) cases=\(sources.count) seconds=\(String(format: "%.6f", seconds)) ops_per_second=\(String(format: "%.0f", opsPerSecond)) parsed=\(parsedCount) diagnostics=\(diagnosticCount) bytes=\(bytes)"
    )
}

private func runPublicApiEdgeChecks() throws {
    let message = try parsePublicApiModel("Hello {$name}")
    let emptyMissing = try formatMessage(
        message,
        onMissingArgument: { _ in "" }
    )
    try expectValue("public-api empty missing recovery", emptyMissing.value, "Hello ")
    try expectCodes(
        "public-api empty missing errors",
        emptyMissing.errors,
        ["unresolved-variable"]
    )

    let emptyMissingParts = try formatMessageToParts(
        message,
        onMissingArgument: { _ in "" }
    )
    try expectParts(
        "public-api empty missing parts",
        emptyMissingParts.parts,
        [.text("Hello "), .fallback(source: "$name", value: "")]
    )

    let declinedMissing = try formatMessage(
        message,
        onMissingArgument: { _ in nil }
    )
    try expectValue("public-api declined missing recovery", declinedMissing.value, "Hello {$name}")

    let declinedMissingParts = try formatMessageToParts(
        message,
        onMissingArgument: { _ in nil }
    )
    try expectParts(
        "public-api declined missing parts",
        declinedMissingParts.parts,
        [.text("Hello "), .fallback(source: "$name", value: nil)]
    )

    let integerMessage = try parsePublicApiModel("Hello {$name :integer}")
    let emptyFormatError = try formatMessage(
        integerMessage,
        arguments: ["name": .string("abc")],
        onFormatError: { _ in "" }
    )
    try expectValue("public-api empty format-error recovery", emptyFormatError.value, "Hello ")
    try expectCodes(
        "public-api empty format-error errors",
        emptyFormatError.errors,
        ["bad-operand"]
    )

    let emptyFormatErrorParts = try formatMessageToParts(
        integerMessage,
        arguments: ["name": .string("abc")],
        onFormatError: { _ in "" }
    )
    try expectParts(
        "public-api empty format-error parts",
        emptyFormatErrorParts.parts,
        [.text("Hello "), .fallback(source: "$name", value: "")]
    )
}

private func checkPublicApiBoundary() throws {
    let sourceDirectory = URL(fileURLWithPath: "Sources/MessageFormat2", isDirectory: true)
    guard FileManager.default.fileExists(atPath: sourceDirectory.path) else {
        return
    }
    guard let enumerator = FileManager.default.enumerator(at: sourceDirectory, includingPropertiesForKeys: nil) else {
        return
    }
    for case let sourceURL as URL in enumerator where sourceURL.pathExtension == "swift" {
        let lines = try String(contentsOf: sourceURL, encoding: .utf8).split(
            separator: "\n",
            omittingEmptySubsequences: false
        )
        for (index, line) in lines.enumerated() {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.hasPrefix("public ") {
                continue
            }
            let normalized = normalizePublicApiName(trimmed)
            if containsInflectionApiName(normalized) {
                throw ConformanceError.publicApiBoundary(
                    source: sourceURL.path,
                    line: index + 1,
                    declaration: trimmed
                )
            }
        }
    }
}

private func containsInflectionApiName(_ normalized: String) -> Bool {
    normalized.contains("inflection")
        || normalized.contains("m2if")
        || normalized.contains("compiledtermpack")
        || normalized.contains("termpack")
}

private func normalizePublicApiName(_ value: String) -> String {
    value.lowercased().filter { character in
        character.isLetter || character.isNumber
    }
}

private func parsePublicApiModel(_ source: String) throws -> MF2Message {
    let parsed = parseToModel(source)
    guard let model = parsed.model, parsed.diagnostics.isEmpty else {
        throw ConformanceError.parseMismatch(
            fixture: "public-api",
            expected: "valid model",
            actual: "\(String(describing: parsed.model)); diagnostics=\(parsed.diagnostics)"
        )
    }
    return model
}

private func expectValue(_ label: String, _ actual: String, _ expected: String) throws {
    if actual != expected {
        throw ConformanceError.formatMismatch(fixture: label, expected: expected, actual: actual)
    }
}

private func expectParts(
    _ label: String,
    _ actual: [MF2FormattedPart],
    _ expected: [MF2FormattedPart]
) throws {
    if actual != expected {
        throw ConformanceError.partsMismatch(
            fixture: label,
            expected: "\(expected)",
            actual: "\(actual)"
        )
    }
}

private func expectCodes(_ label: String, _ actual: [MF2Error], _ expected: [String]) throws {
    let actualCodes = actual.map(\.code)
    if actualCodes != expected {
        throw ConformanceError.errorCodesMismatch(
            fixture: "public-api",
            label: label,
            expected: "\(expected)",
            actual: "\(actualCodes)"
        )
    }
}

private func runFormatErrorFixtures(fixtureRoot: URL) throws -> Int {
    let fixtureDirectory = fixtureRoot.appendingPathComponent("format-errors")
    guard FileManager.default.fileExists(atPath: fixtureDirectory.path) else {
        return 0
    }

    var checkedCases = 0
    for fixtureURL in try fixtureURLs(in: fixtureDirectory) {
        let fixture = try JSONDecoder().decode(
            FormatErrorFixture.self,
            from: Data(contentsOf: fixtureURL)
        )

        do {
            let actual = try fixture.model.format(
                arguments: fixture.arguments,
                locale: fixture.locale
            )
            if !actual.errors.contains(where: { $0.code == fixture.expectedError.code }) {
                throw ConformanceError.expectedFormatError(
                    fixture: fixtureURL.lastPathComponent,
                    actual: actual.value
                )
            }
        } catch let error as MF2Error {
            if error.code != fixture.expectedError.code {
                throw ConformanceError.formatErrorMismatch(
                    fixture: fixtureURL.lastPathComponent,
                    expected: fixture.expectedError.code,
                    actual: error.code
                )
            }
        }

        checkedCases += 1
    }

    return checkedCases
}

private func runInvalidSourceFixtures(fixtureRoot: URL) throws -> Int {
    let fixtureDirectory = fixtureRoot.appendingPathComponent("invalid-source")
    guard FileManager.default.fileExists(atPath: fixtureDirectory.path) else {
        return 0
    }

    var checkedCases = 0
    for fixtureURL in try fixtureURLs(in: fixtureDirectory) {
        let fixture = try JSONDecoder().decode(
            InvalidSourceFixture.self,
            from: Data(contentsOf: fixtureURL)
        )
        let actualCodes = parseToModel(fixture.source).diagnostics.map(\.code)
        let expectedCodes = fixture.expectedDiagnostics.map(\.code)
        if actualCodes != expectedCodes {
            throw ConformanceError.sourceDiagnosticsMismatch(
                fixture: fixtureURL.lastPathComponent,
                expected: "\(expectedCodes)",
                actual: "\(actualCodes)"
            )
        }
        checkedCases += 1
    }

    return checkedCases
}

private func runLocaleKeyFixtures(fixtureRoot: URL) throws -> Int {
    let fixtureURL = fixtureRoot
        .appendingPathComponent("locale-key")
        .appendingPathComponent("cases.json")
    guard FileManager.default.fileExists(atPath: fixtureURL.path) else {
        return 0
    }

    let fixture = try JSONDecoder().decode(
        LocaleKeyFixture.self,
        from: Data(contentsOf: fixtureURL)
    )

    var checkedCases = 0
    for item in fixture.canonical {
        let actual = canonicalLocaleKey(item.source)
        if actual != item.expected {
            throw ConformanceError.localeKeyMismatch(
                fixture: fixtureURL.lastPathComponent,
                expected: item.expected,
                actual: actual
            )
        }
        checkedCases += 1
    }

    for item in fixture.lookupChains {
        let actual = localeLookupChain(item.source)
        if actual != item.expected {
            throw ConformanceError.localeKeyMismatch(
                fixture: fixtureURL.lastPathComponent,
                expected: "\(item.expected)",
                actual: "\(actual)"
            )
        }
        checkedCases += 1
    }

    return checkedCases
}

private func assertErrorCodes(
    fixture: String,
    label: String,
    actual: [MF2Error],
    expected: [ExpectedError]
) throws {
    let actualCodes = actual.map(\.code)
    let expectedCodes = expected.map(\.code)
    if actualCodes != expectedCodes {
        throw ConformanceError.errorCodesMismatch(
            fixture: fixture,
            label: label,
            expected: "\(expectedCodes)",
            actual: "\(actualCodes)"
        )
    }
}

private func fixtureURLs(in directory: URL) throws -> [URL] {
    try FileManager.default
        .contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil
        )
        .filter { $0.pathExtension == "json" }
        .sorted { $0.lastPathComponent < $1.lastPathComponent }
}

private struct SourceToModelFixture: Decodable {
    let source: String
    let expectedModel: MF2Message
    let formatCases: [FormatCase]
    let partsCases: [PartsCase]
    let fallbackCases: [FallbackCase]
    let fallbackPartsCases: [FallbackPartsCase]

    private enum CodingKeys: String, CodingKey {
        case source
        case expectedModel
        case formatCases
        case partsCases
        case fallbackCases
        case fallbackPartsCases
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        source = try container.decode(String.self, forKey: .source)
        expectedModel = try container.decode(MF2Message.self, forKey: .expectedModel)
        formatCases = try container.decodeIfPresent([FormatCase].self, forKey: .formatCases) ?? []
        partsCases = try container.decodeIfPresent([PartsCase].self, forKey: .partsCases) ?? []
        fallbackCases = try container.decodeIfPresent([FallbackCase].self, forKey: .fallbackCases) ?? []
        fallbackPartsCases = try container.decodeIfPresent(
            [FallbackPartsCase].self,
            forKey: .fallbackPartsCases
        ) ?? []
    }
}

private struct SourceOnlyFixture: Decodable {
    let source: String?
}

private struct InvalidSourceFixture: Decodable {
    let source: String
    let expectedDiagnostics: [ExpectedError]
}

private struct FormatCase: Decodable {
    let locale: String
    let bidiIsolation: MF2BidiIsolation?
    let arguments: [String: MF2Value]
    let expected: String
}

private struct PartsCase: Decodable {
    let locale: String
    let arguments: [String: MF2Value]
    let expected: [MF2FormattedPart]
}

private struct FallbackCase: Decodable {
    let locale: String
    let bidiIsolation: MF2BidiIsolation?
    let arguments: [String: MF2Value]
    let expected: String
    let expectedErrors: [ExpectedError]
}

private struct FallbackPartsCase: Decodable {
    let locale: String
    let arguments: [String: MF2Value]
    let expected: [MF2FormattedPart]
    let expectedErrors: [ExpectedError]
}

private struct FormatErrorFixture: Decodable {
    let model: MF2Message
    let locale: String
    let arguments: [String: MF2Value]
    let expectedError: ExpectedError
}

private struct ExpectedError: Decodable {
    let code: String
}

private struct LocaleKeyFixture: Decodable {
    let canonical: [LocaleCanonicalCase]
    let lookupChains: [LocaleLookupChainCase]
}

private struct LocaleCanonicalCase: Decodable {
    let source: String
    let expected: String
}

private struct LocaleLookupChainCase: Decodable {
    let source: String
    let expected: [String]
}

private struct BenchCase {
    let model: MF2Message
    let locale: String
    let arguments: [String: MF2Value]
}

private func canonicalLocaleKey(_ locale: String) -> String {
    localeParts(locale).joined(separator: "-")
}

private func localeLookupChain(_ locale: String) -> [String] {
    let parts = canonicalLocaleKey(locale).split(separator: "-").map(String.init)
    return stride(from: parts.count, through: 1, by: -1)
        .map { parts.prefix($0).joined(separator: "-") }
}

private func localeParts(_ locale: String) -> [String] {
    var output: [String] = []
    let rawParts = locale
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .replacingOccurrences(of: "_", with: "-")
        .split(separator: "-")
        .map(String.init)
    for (index, part) in rawParts.enumerated() {
        if part.count == 1 {
            break
        }
        output.append(canonicalSubtag(index: index, part: part))
    }
    return output
}

private func canonicalSubtag(index: Int, part: String) -> String {
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

private enum ConformanceError: Error, CustomStringConvertible {
    case noFormatCases
    case noSourceCases
    case parseMismatch(fixture: String, expected: String, actual: String)
    case formatMismatch(fixture: String, expected: String, actual: String)
    case partsMismatch(fixture: String, expected: String, actual: String)
    case expectedNoFormatErrors(fixture: String, actual: [String])
    case expectedFormatError(fixture: String, actual: String)
    case formatErrorMismatch(fixture: String, expected: String, actual: String)
    case errorCodesMismatch(fixture: String, label: String, expected: String, actual: String)
    case sourceDiagnosticsMismatch(fixture: String, expected: String, actual: String)
    case localeKeyMismatch(fixture: String, expected: String, actual: String)
    case publicApiBoundary(source: String, line: Int, declaration: String)

    var description: String {
        switch self {
        case .noFormatCases:
            "No format cases found."
        case .noSourceCases:
            "No source cases found."
        case let .parseMismatch(fixture, expected, actual):
            "\(fixture): expected parsed model '\(expected)', got '\(actual)'"
        case let .formatMismatch(fixture, expected, actual):
            "\(fixture): expected '\(expected)', got '\(actual)'"
        case let .partsMismatch(fixture, expected, actual):
            "\(fixture): expected parts '\(expected)', got '\(actual)'"
        case let .expectedNoFormatErrors(fixture, actual):
            "\(fixture): expected no format errors, got '\(actual)'"
        case let .expectedFormatError(fixture, actual):
            "\(fixture): expected format error, got '\(actual)'"
        case let .formatErrorMismatch(fixture, expected, actual):
            "\(fixture): expected error '\(expected)', got '\(actual)'"
        case let .errorCodesMismatch(fixture, label, expected, actual):
            "\(fixture): expected \(label) '\(expected)', got '\(actual)'"
        case let .sourceDiagnosticsMismatch(fixture, expected, actual):
            "\(fixture): expected source diagnostics '\(expected)', got '\(actual)'"
        case let .localeKeyMismatch(fixture, expected, actual):
            "\(fixture): expected locale key '\(expected)', got '\(actual)'"
        case let .publicApiBoundary(source, line, declaration):
            "\(source):\(line): standalone Swift MF2 must not expose public inflection/M2IF/term-pack APIs before a product API is approved: \(declaration)"
        }
    }
}
