import Foundation
import MessageFormat2

do {
    let arguments = Array(CommandLine.arguments.dropFirst())
    if arguments.first == "official-bridge" { try runOfficialBridge(); Foundation.exit(0) }
    if arguments.first == "--bench" {
        try runBenchmark(arguments: Array(arguments.dropFirst()))
        Foundation.exit(0)
    }
    if arguments.first == "--bench-parse" {
        try runParseBenchmark(arguments: Array(arguments.dropFirst()))
        Foundation.exit(0)
    }

    try runRuntimeRegressionChecks()
    try runBenchmarkRegressionChecks()
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
        let roundTripped = try JSONDecoder().decode(MF2Message.self, from: JSONEncoder().encode(fixture.expectedModel))
        if roundTripped != fixture.expectedModel {
            throw ConformanceError.parseMismatch(fixture: fixtureURL.lastPathComponent,
                expected: "\(fixture.expectedModel)", actual: "\(roundTripped)")
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

    guard checkedSourceCases > 0 else {
        throw ConformanceError.noSourceCases
    }
    guard checkedCases > 0 else {
        throw ConformanceError.noFormatCases
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
    let iterations = try benchmarkCount(arguments.dropFirst().first ?? "100000", label: "iteration", minimum: 1)
    let warmupIterations = try benchmarkCount(arguments.dropFirst(2).first ?? "10000", label: "warmup", minimum: 0)
    let cases = try fixtureURLs(in: fixtureDirectory).flatMap { fixtureURL in
        let fixture = try JSONDecoder().decode(
            SourceToModelFixture.self,
            from: Data(contentsOf: fixtureURL)
        )
        return fixture.formatCases.map { formatCase in
            BenchCase(
                model: fixture.expectedModel,
                locale: formatCase.locale,
                arguments: formatCase.arguments,
                bidiIsolation: formatCase.bidiIsolation ?? .none,
                expected: formatCase.expected
            )
        }
    }

    guard !cases.isEmpty else {
        throw ConformanceError.noFormatCases
    }

    // Check the whole corpus before warmup/timing, including cases not reached
    // by a short measured run.
    for benchCase in cases {
        let output = try benchCase.format()
        if output != benchCase.expected {
            throw ConformanceError.formatMismatch(fixture: "benchmark preflight", expected: benchCase.expected, actual: output)
        }
    }

    for index in 0..<warmupIterations {
        _ = try cases[index % cases.count].format()
    }

    let started = Date()
    var bytes = 0
    for index in 0..<iterations {
        let output = try cases[index % cases.count].format()
        bytes += output.utf8.count
    }
    let seconds = Date().timeIntervalSince(started)
    let opsPerSecond = Double(iterations) / seconds
    print(
        "swift format iterations=\(iterations) warmup=\(warmupIterations) cases=\(cases.count) seconds=\(String(format: "%.6f", seconds)) ops_per_second=\(String(format: "%.0f", opsPerSecond)) bytes=\(bytes)"
    )
}

private func runParseBenchmark(arguments: [String]) throws {
    let fixtureDirectory = try resolveFixtureDirectory(arguments: arguments)
    let iterations = try benchmarkCount(arguments.dropFirst().first ?? "100000", label: "iteration", minimum: 1)
    let warmupIterations = try benchmarkCount(arguments.dropFirst(2).first ?? "10000", label: "warmup", minimum: 0)
    let fixtures = try fixtureURLs(in: fixtureDirectory).map { fixtureURL in
        try JSONDecoder().decode(
            SourceOnlyFixture.self,
            from: Data(contentsOf: fixtureURL)
        )
    }

    guard !fixtures.isEmpty else {
        throw ConformanceError.noSourceCases
    }

    for fixture in fixtures {
        let result = parseToModel(fixture.source)
        let actual = result.diagnostics.map(\.code)
        let expected = (fixture.expectedDiagnostics ?? []).map(\.code)
        guard actual == expected, (result.model != nil) == expected.isEmpty else {
            throw ConformanceError.sourceDiagnosticsMismatch(
                fixture: "parser benchmark preflight",
                expected: "\(expected), model=\(expected.isEmpty)",
                actual: "\(actual), model=\(result.model != nil)")
        }
    }
    let sources = fixtures.map(\.source)

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

private func benchmarkCount(_ value: String, label: String, minimum: Int) throws -> Int {
    guard let count = Int(value), count >= minimum else {
        throw ConformanceError.invalidBenchmarkCount(label: label, value: value)
    }
    return count
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

    let differentialCases: [(
        label: String,
        source: String,
        arguments: [String: MF2Value],
        expected: String,
        errors: [String]
    )] = [
        (
            "exact numeric key precedence",
            ".input {$count :integer}\n.match $count\none {{category}}\n1 {{exact}}\n* {{fallback}}",
            ["count": .number("1")],
            "exact",
            []
        ),
        (
            "canonical exact numeric serialization",
            ".input {$value :number}\n.match $value\n1.0 {{decimal}}\n1 {{integer}}\n* {{fallback}}",
            ["value": .number("1")],
            "integer",
            []
        ),
        (
            "invalid numeric key continuation",
            ".input {$value :number}\n.match $value\nhorse {{horse}}\n1 {{exact}}\n* {{fallback}}",
            ["value": .number("1")],
            "exact",
            ["bad-variant-key"]
        ),
        (
            "failed input fallback",
            ".input {$foo :number} {{bar {$foo}}}",
            ["foo": .string("foo")],
            "bar {$foo}",
            ["bad-operand"]
        ),
        (
            "inherited number options",
            ".local $n = {1.2 :number minimumFractionDigits=2} {{Value {$n :number}}}",
            [:],
            "Value 1.20",
            []
        ),
        (
            "maximum fraction digits",
            "Value {1.29 :number maximumFractionDigits=1}",
            [:],
            "Value 1.3",
            []
        ),
        (
            "maximum fraction digits accepted boundary",
            "Value {1 :number maximumFractionDigits=1000}",
            [:],
            "Value 1",
            []
        ),
        (
            "minimum fraction digits accepted boundary",
            "Value {1 :number minimumFractionDigits=1000}",
            [:],
            "Value 1." + String(repeating: "0", count: 1_000),
            []
        ),
        (
            "minimum integer offset subtraction",
            "{-1 :offset subtract=-9223372036854775808}",
            [:],
            "9223372036854775807",
            []
        ),
        (
            "minimum integer offset source chain",
            ".local $offset = {-1 :offset subtract=-9223372036854775808} "
                + "{{Value {$offset :offset add=0}}}",
            [:],
            "Value 9223372036854775807",
            []
        ),
        (
                "exact integral number",
                "{9223372036854775807 :number}",
            [:],
                "9223372036854775807",
                []
            ),
        (
                "exact offset number reannotation",
                ".local $offset = {-1 :offset subtract=-9223372036854775808} "
                + "{{Value {$offset :number}}}",
            [:],
                "Value 9223372036854775807",
                []
            ),
        (
                "exact integral integer",
                "{9223372036854775807 :integer}",
            [:],
                "9223372036854775807",
                []
            ),
        (
                "exact offset integer reannotation",
                ".local $offset = {-1 :offset subtract=-9223372036854775808} "
                + "{{Value {$offset :integer}}}",
            [:],
                "Value 9223372036854775807",
                []
            ),
        (
            "excessive maximum fraction digits",
            "Value {1 :number maximumFractionDigits=65536}",
            [:],
            "Value {|1|}",
            ["bad-option"]
        ),
        (
            "excessive minimum fraction digits",
            "Value {1 :number minimumFractionDigits=65536}",
            [:],
            "Value {|1|}",
            ["bad-option"]
        ),
        (
                "large exact percent",
                "{1E308 :percent}",
            [:],
                "1" + String(repeating: "0", count: 310) + "%",
                []
            ),
        (
                "large exact offset",
                "{1E127 :offset add=9223372036854775807}",
            [:],
                "1" + String(repeating: "0", count: 108) + "9223372036854775807",
                []
            ),
        (
            "variable numeric select",
            "variable select {1 :number select=$bad}",
            ["bad": .string("exact")],
            "variable select 1",
            ["bad-option"]
        ),
        (
            "inherited exact select",
            ".local $sel = {1 :number select=exact} "
                + ".local $bad = {$sel :number} "
                + ".match $bad 1 {{ONE}} * {{operand select {$bad}}}",
            [:],
            "operand select 1",
            ["bad-option", "bad-selector"]
        ),
    ]
    for item in differentialCases {
        let model = try parsePublicApiModel(item.source)
        let result = try formatMessage(model, arguments: item.arguments)
        try expectValue("public-api \(item.label)", result.value, item.expected)
        try expectCodes("public-api \(item.label) errors", result.errors, item.errors)
    }

    let permissiveInteger = MF2FunctionRegistry.portable.withFunction("integer") { call in
        call.value
    }
    let integerSelector = try parsePublicApiModel(
        ".input {$value :integer select=exact}\n.match $value\n"
            + "9223372036854775807 {{exact}}\n* {{fallback}}"
    )
    let integerSelectorResult = try formatMessage(
        integerSelector,
        arguments: ["value": .number("9223372036854775807")],
        functions: permissiveInteger
    )
    try expectValue(
        "public-api exact large integer selector",
        integerSelectorResult.value,
        "exact"
    )
    try expectCodes(
        "public-api exact large integer selector errors",
        integerSelectorResult.errors,
        []
    )

    let permissiveNumber = MF2FunctionRegistry.portable.withFunction("number") { call in
        call.value
    }
    let inheritedFractionSelector = try parsePublicApiModel(
        ".local $source = {1 :number minimumFractionDigits=65536} "
            + ".local $selected = {$source :number} "
            + ".match $selected one {{one}} * {{fallback}}"
    )
    let inheritedFractionResult = try formatMessage(
        inheritedFractionSelector,
        functions: permissiveNumber
    )
    try expectValue(
        "public-api inherited excessive fraction selector",
        inheritedFractionResult.value,
        "fallback"
    )
    try expectCodes(
        "public-api inherited excessive fraction selector errors",
        inheritedFractionResult.errors,
        ["bad-option", "bad-selector"]
    )

    let permissivePercent = MF2FunctionRegistry.portable.withFunction("percent") { call in
        call.value
    }
    let percentSelector = try parsePublicApiModel(
        ".input {$value :percent}\n.match $value\nother {{other}}\n* {{fallback}}"
    )
    let percentSelectorResult = try formatMessage(
        percentSelector,
        arguments: ["value": .number("1E308")],
        functions: permissivePercent
    )
    try expectValue(
        "public-api overflowing percent selector",
        percentSelectorResult.value,
        "fallback"
    )
    try expectCodes(
        "public-api overflowing percent selector errors",
        percentSelectorResult.errors,
        ["bad-selector"]
    )
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
        let data = try Data(contentsOf: fixtureURL)
        let fixture: FormatErrorFixture
        do {
            fixture = try JSONDecoder().decode(FormatErrorFixture.self, from: data)
        } catch is DecodingError {
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            guard (json?["expectedError"] as? [String: Any])?["code"] as? String == "invalid-model" else {
                throw ConformanceError.expectedFormatError(
                    fixture: fixtureURL.lastPathComponent, actual: "model decode error")
            }
            checkedCases += 1
            continue
        }

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
    let source: String
    let expectedDiagnostics: [ExpectedError]?
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
    private enum CodingKeys: String, CodingKey { case locale, bidiIsolation, arguments, expected }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        locale = try c.decodeIfPresent(String.self, forKey: .locale) ?? "en"
        bidiIsolation = try c.decodeIfPresent(MF2BidiIsolation.self, forKey: .bidiIsolation)
        arguments = try c.decodeIfPresent([String: MF2Value].self, forKey: .arguments) ?? [:]
        expected = try c.decode(String.self, forKey: .expected)
    }
}

private struct PartsCase: Decodable {
    let locale: String
    let arguments: [String: MF2Value]
    let expected: [MF2FormattedPart]
    private enum CodingKeys: String, CodingKey { case locale, arguments, expected }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        locale = try c.decodeIfPresent(String.self, forKey: .locale) ?? "en"
        arguments = try c.decodeIfPresent([String: MF2Value].self, forKey: .arguments) ?? [:]
        expected = try c.decode([MF2FormattedPart].self, forKey: .expected)
    }
}

private struct FallbackCase: Decodable {
    let locale: String
    let bidiIsolation: MF2BidiIsolation?
    let arguments: [String: MF2Value]
    let expected: String
    let expectedErrors: [ExpectedError]
    private enum CodingKeys: String, CodingKey { case locale, bidiIsolation, arguments, expected, expectedErrors }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        locale = try c.decodeIfPresent(String.self, forKey: .locale) ?? "en"
        bidiIsolation = try c.decodeIfPresent(MF2BidiIsolation.self, forKey: .bidiIsolation)
        arguments = try c.decodeIfPresent([String: MF2Value].self, forKey: .arguments) ?? [:]
        expected = try c.decode(String.self, forKey: .expected)
        expectedErrors = try c.decode([ExpectedError].self, forKey: .expectedErrors)
    }
}

private struct FallbackPartsCase: Decodable {
    let locale: String
    let arguments: [String: MF2Value]
    let expected: [MF2FormattedPart]
    let expectedErrors: [ExpectedError]
    private enum CodingKeys: String, CodingKey { case locale, arguments, expected, expectedErrors }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        locale = try c.decodeIfPresent(String.self, forKey: .locale) ?? "en"
        arguments = try c.decodeIfPresent([String: MF2Value].self, forKey: .arguments) ?? [:]
        expected = try c.decode([MF2FormattedPart].self, forKey: .expected)
        expectedErrors = try c.decode([ExpectedError].self, forKey: .expectedErrors)
    }
}

private struct FormatErrorFixture: Decodable {
    let model: MF2Message
    let locale: String
    let arguments: [String: MF2Value]
    let expectedError: ExpectedError
    private enum CodingKeys: String, CodingKey { case model, locale, arguments, expectedError }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        model = try c.decode(MF2Message.self, forKey: .model)
        locale = try c.decodeIfPresent(String.self, forKey: .locale) ?? "en"
        arguments = try c.decodeIfPresent([String: MF2Value].self, forKey: .arguments) ?? [:]
        expectedError = try c.decode(ExpectedError.self, forKey: .expectedError)
    }
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
    let bidiIsolation: MF2BidiIsolation
    let expected: String

    func format() throws -> String {
        let output = try model.format(arguments: arguments, locale: locale, bidiIsolation: bidiIsolation)
        if output.hasErrors {
            throw ConformanceError.expectedNoFormatErrors(fixture: "bench", actual: output.errors.map(\.code))
        }
        return output.value
    }
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
    case invalidBenchmarkCount(label: String, value: String)
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

    var description: String {
        switch self {
        case let .invalidBenchmarkCount(label, value):
            "Invalid \(label) count: '\(value)'"
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
        }
    }
}
