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
    if arguments.first == "--number-core" {
        try runNumberCoreCheck(arguments: Array(arguments.dropFirst()))
        Foundation.exit(0)
    }
    if arguments.first == "--number-core-bench" {
        try runNumberCoreBenchmark(arguments: Array(arguments.dropFirst()))
        Foundation.exit(0)
    }
    if arguments.first == "--date-time-core" {
        try runDateTimeCoreCheck(arguments: Array(arguments.dropFirst()))
        Foundation.exit(0)
    }
    if arguments.first == "--date-time-core-bench" {
        try runDateTimeCoreBenchmark(arguments: Array(arguments.dropFirst()))
        Foundation.exit(0)
    }
    if arguments.first == "--relative-time-core" {
        try runRelativeTimeCoreCheck(arguments: Array(arguments.dropFirst()))
        Foundation.exit(0)
    }
    if arguments.first == "--relative-time-core-bench" {
        try runRelativeTimeCoreBenchmark(arguments: Array(arguments.dropFirst()))
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

private func runNumberCoreCheck(arguments: [String]) throws {
    let fixtureURL = resolveNumberCoreFixture(arguments: arguments)
    let fixture = try JSONDecoder().decode(
        NumberCoreFixture.self,
        from: Data(contentsOf: fixtureURL)
    )

    var checkedFormatCases = 0
    for item in fixture.formatCases {
        let actual = try MF2NumberCore.format(item.value, options: item.options.runtimeOptions(locale: item.locale))
        if actual != item.expected {
            throw ConformanceError.formatMismatch(
                fixture: item.name,
                expected: item.expected,
                actual: actual
            )
        }
        checkedFormatCases += 1
    }

    var checkedReferenceCases = 0
    var skippedReferenceCases = 0
    for item in fixture.intlReferenceCases {
        let actual = try MF2NumberCore.format(item.value, options: item.options.runtimeOptions(locale: item.locale))
        let expected = try foundationNumberFormat(locale: item.locale, value: item.value, options: item.options)
        if isKnownFoundationNumberDifference(item, actual: actual, expected: expected) {
            skippedReferenceCases += 1
            continue
        }
        if actual != expected {
            throw ConformanceError.formatMismatch(
                fixture: "Foundation reference \(item.locale) \(item.options)",
                expected: expected,
                actual: actual
            )
        }
        checkedReferenceCases += 1
    }

    var checkedErrorCases = 0
    for item in fixture.errorCases {
        do {
            _ = try MF2NumberCore.format(item.value, options: item.options.runtimeOptions(locale: item.locale))
            throw ConformanceError.expectedFormatError(fixture: item.name, actual: "success")
        } catch let error as MF2Error {
            if error.code != item.expectedError {
                throw ConformanceError.formatErrorMismatch(
                    fixture: item.name,
                    expected: item.expectedError,
                    actual: error.code
                )
            }
        }
        checkedErrorCases += 1
    }

    let hugeCoreInteger = try MF2NumberCore.format(
        .number("100000000000000000000.9"),
        options: MF2NumberCore.Options(style: .integer, useGrouping: false)
    )
    try expectValue("number-core huge integer truncation", hugeCoreInteger, "100000000000000000000")
    let negativeCoreInteger = try MF2NumberCore.format(
        .number("-123.9"),
        options: MF2NumberCore.Options(style: .integer, useGrouping: false)
    )
    try expectValue("number-core negative integer truncation", negativeCoreInteger, "-123")
    let numberPartsValue = try MF2NumberCore.format(.number("1234.5"), options: MF2NumberCore.Options(locale: "en-US"))
    let numberParts = try MF2NumberCore.formatToParts(.number("1234.5"), options: MF2NumberCore.Options(locale: "en-US"))
    try expectParts("number-core direct parts", numberParts, [.text(numberPartsValue)])

    try runNumberCoreRegistryIntegration(fixture: fixture)
    print(
        "Swift number core test passed \(checkedFormatCases) format cases, \(checkedReferenceCases) Foundation reference cases, \(skippedReferenceCases) known Foundation differences, \(checkedErrorCases) error cases, and registry integration."
    )
}

private func runNumberCoreBenchmark(arguments: [String]) throws {
    let fixtureURL = resolveNumberCoreFixture(arguments: arguments)
    let iterations = Int(arguments.dropFirst().first ?? "100000") ?? 100000
    let warmupIterations = Int(arguments.dropFirst(2).first ?? "10000") ?? 10000
    let fixture = try JSONDecoder().decode(
        NumberCoreFixture.self,
        from: Data(contentsOf: fixtureURL)
    )
    let formatCases = try fixture.formatCases.map { item in
        NumberCoreBenchCase(
            value: item.value,
            options: try item.options.runtimeOptions(locale: item.locale)
        )
    }
    let referenceCases = try fixture.intlReferenceCases.map { item in
        FoundationNumberBenchCase(
            value: item.value,
            formatter: try foundationNumberFormatter(locale: item.locale, options: item.options)
        )
    }
    try runNumberCoreBenchmark(
        label: "swift-number-core-format",
        cases: formatCases,
        iterations: iterations,
        warmupIterations: warmupIterations
    ) { try MF2NumberCore.format($0.value, options: $0.options) }
    try runNumberCoreBenchmark(
        label: "swift-foundation-number-format",
        cases: referenceCases,
        iterations: iterations,
        warmupIterations: warmupIterations
    ) { item in
        guard let output = item.formatter.string(from: item.value.numberValue) else {
            throw ConformanceError.expectedFormatError(fixture: "swift-foundation-number-format", actual: "nil")
        }
        return output
    }
}

private func runNumberCoreBenchmark<T>(
    label: String,
    cases: [T],
    iterations: Int,
    warmupIterations: Int,
    formatter: (T) throws -> String
) throws {
    for index in 0..<warmupIterations {
        _ = try formatter(cases[index % cases.count])
    }

    let started = Date()
    var bytes = 0
    for index in 0..<iterations {
        bytes += try formatter(cases[index % cases.count]).utf8.count
    }
    let seconds = Date().timeIntervalSince(started)
    let opsPerSecond = Double(iterations) / seconds
    print(
        "\(label) iterations=\(iterations) warmup=\(warmupIterations) cases=\(cases.count) seconds=\(String(format: "%.6f", seconds)) ns_per_op=\(String(format: "%.1f", seconds * 1_000_000_000 / Double(iterations))) ops_per_second=\(String(format: "%.0f", opsPerSecond)) bytes=\(bytes)"
    )
}

private func runNumberCoreRegistryIntegration(fixture: NumberCoreFixture) throws {
    let registry = MF2NumberCore.registry()
    let currencyModel = try parsePublicApiModel("Total: {$amount :currency currency=USD}")
    let currencyResult = try formatMessage(
        currencyModel,
        arguments: ["amount": .number("1234.5")],
        locale: "en-US",
        functions: registry
    )
    try expectValue("number-core registry currency", currencyResult.value, "Total: $1,234.50")
    if currencyResult.hasErrors {
        throw ConformanceError.expectedNoFormatErrors(
            fixture: "number-core registry currency",
            actual: currencyResult.errors.map(\.code)
        )
    }

    let selectorModel = try parsePublicApiModel(".input {$count :number}\n.match $count\none {{one}}\n* {{other}}")
    let selectorResult = try formatMessage(
        selectorModel,
        arguments: ["count": .number("1")],
        locale: "en",
        functions: registry
    )
    try expectValue("number-core registry selector", selectorResult.value, "one")
    if selectorResult.hasErrors {
        throw ConformanceError.expectedNoFormatErrors(
            fixture: "number-core registry selector",
            actual: selectorResult.errors.map(\.code)
        )
    }

    for item in fixture.registryCases ?? [] {
        let model = try parsePublicApiModel(item.source)
        let result = try formatMessage(
            model,
            arguments: item.arguments,
            locale: item.locale,
            functions: registry
        )
        try expectValue(item.name, result.value, item.expected)
        if result.hasErrors {
            throw ConformanceError.expectedNoFormatErrors(
                fixture: item.name,
                actual: result.errors.map(\.code)
            )
        }
    }

    for item in fixture.registryErrorCases ?? [] {
        let model = try parsePublicApiModel(item.source)
        let result = try formatMessage(
            model,
            arguments: item.arguments,
            locale: item.locale,
            functions: registry
        )
        let actual = result.errors.map(\.code)
        if actual != item.expectedErrors {
            throw ConformanceError.formatErrorMismatch(
                fixture: item.name,
                expected: item.expectedErrors.joined(separator: ","),
                actual: actual.joined(separator: ",")
            )
        }
    }
}

private func runDateTimeCoreCheck(arguments: [String]) throws {
    let fixtureURL = resolveDateTimeCoreFixture(arguments: arguments)
    let fixture = try JSONDecoder().decode(
        DateTimeCoreFixture.self,
        from: Data(contentsOf: fixtureURL)
    )

    var checkedFormatCases = 0
    for item in fixture.formatCases {
        let actual = try dateTimeCoreFormat(item.kind, value: item.value, options: item.options.runtimeOptions(locale: item.locale))
        if actual != item.expected {
            throw ConformanceError.formatMismatch(
                fixture: item.name,
                expected: item.expected,
                actual: actual
            )
        }
        checkedFormatCases += 1
    }

    var checkedReferenceCases = 0
    var skippedReferenceCases = 0
    for item in fixture.intlReferenceCases {
        let actual = try dateTimeCoreFormat(item.kind, value: item.value, options: item.options.runtimeOptions(locale: item.locale))
        let expected = try foundationDateTimeFormat(kind: item.kind, locale: item.locale, value: item.value, options: item.options)
        if isKnownFoundationDateTimeDifference(item, actual: actual, expected: expected) {
            skippedReferenceCases += 1
            continue
        }
        if actual != expected {
            throw ConformanceError.formatMismatch(
                fixture: "Foundation reference \(item.kind) \(item.locale) \(item.options)",
                expected: expected,
                actual: actual
            )
        }
        checkedReferenceCases += 1
    }

    var checkedSemanticReferenceCases = 0
    for item in fixture.semanticStyleReferenceCases ?? [] {
        let actual = try dateTimeCoreFormat(item.kind, value: item.value, options: item.options.runtimeOptions(locale: item.locale))
        let referenceOptions = item.referenceOptions ?? item.options
        let expected = try foundationDateTimeFormat(kind: item.kind, locale: item.locale, value: item.value, options: referenceOptions)
        if actual != expected {
            throw ConformanceError.formatMismatch(
                fixture: "Foundation semantic style reference \(item.name ?? item.kind)",
                expected: expected,
                actual: actual
            )
        }
        checkedSemanticReferenceCases += 1
    }

    var checkedErrorCases = 0
    for item in fixture.errorCases {
        do {
            _ = try dateTimeCoreFormat(item.kind, value: item.value, options: item.options.runtimeOptions(locale: item.locale))
            throw ConformanceError.expectedFormatError(fixture: item.name, actual: "success")
        } catch let error as MF2Error {
            if error.code != item.expectedError {
                throw ConformanceError.formatErrorMismatch(
                    fixture: item.name,
                    expected: item.expectedError,
                    actual: error.code
                )
            }
        }
        checkedErrorCases += 1
    }

    let directPartsValue = "2026-05-21T14:30:15Z"
    let datePartsValue = try MF2DateTimeCore.formatDate(.string(directPartsValue), options: MF2DateTimeCore.Options(locale: "en-US"))
    try expectParts(
        "date-time-core date direct parts",
        try MF2DateTimeCore.formatDateToParts(.string(directPartsValue), options: MF2DateTimeCore.Options(locale: "en-US")),
        [.text(datePartsValue)]
    )
    let timePartsValue = try MF2DateTimeCore.formatTime(.string(directPartsValue), options: MF2DateTimeCore.Options(locale: "en-US"))
    try expectParts(
        "date-time-core time direct parts",
        try MF2DateTimeCore.formatTimeToParts(.string(directPartsValue), options: MF2DateTimeCore.Options(locale: "en-US")),
        [.text(timePartsValue)]
    )
    let dateTimePartsValue = try MF2DateTimeCore.formatDateTime(.string(directPartsValue), options: MF2DateTimeCore.Options(locale: "en-US"))
    try expectParts(
        "date-time-core datetime direct parts",
        try MF2DateTimeCore.formatDateTimeToParts(.string(directPartsValue), options: MF2DateTimeCore.Options(locale: "en-US")),
        [.text(dateTimePartsValue)]
    )

    let checkedRegistryFormatCases = try runDateTimeCoreRegistryCases(fixture.registryFormatCases ?? [])
    let checkedRegistryErrorCases = try runDateTimeCoreRegistryErrorCases(fixture.registryErrorCases ?? [])
    try runDateTimeCoreRegistryIntegration()
    print(
        "Swift date/time core test passed \(checkedFormatCases) format cases, \(checkedReferenceCases) Foundation reference cases, \(checkedSemanticReferenceCases) semantic style reference cases, \(skippedReferenceCases) known Foundation differences, \(checkedErrorCases) error cases, \(checkedRegistryFormatCases) registry format cases, \(checkedRegistryErrorCases) registry error cases, and registry integration."
    )
}

private func runDateTimeCoreBenchmark(arguments: [String]) throws {
    let fixtureURL = resolveDateTimeCoreFixture(arguments: arguments)
    let iterations = Int(arguments.dropFirst().first ?? "100000") ?? 100000
    let warmupIterations = Int(arguments.dropFirst(2).first ?? "10000") ?? 10000
    let fixture = try JSONDecoder().decode(
        DateTimeCoreFixture.self,
        from: Data(contentsOf: fixtureURL)
    )
    let formatCases = try fixture.formatCases.map { item in
        DateTimeCoreBenchCase(
            kind: item.kind,
            value: item.value,
            options: try item.options.runtimeOptions(locale: item.locale)
        )
    }
    let referenceCases = try fixture.intlReferenceCases.map { item in
        FoundationDateTimeBenchCase(
            value: try dateTimeCoreDate(item.value),
            formatter: try foundationDateTimeFormatter(kind: item.kind, locale: item.locale, options: item.options)
        )
    }
    try runNumberCoreBenchmark(
        label: "swift-datetime-core-format",
        cases: formatCases,
        iterations: iterations,
        warmupIterations: warmupIterations
    ) { try dateTimeCoreFormat($0.kind, value: $0.value, options: $0.options) }
    try runNumberCoreBenchmark(
        label: "swift-foundation-datetime-format",
        cases: referenceCases,
        iterations: iterations,
        warmupIterations: warmupIterations
    ) { item in
        item.formatter.string(from: item.value)
    }
}

private func runDateTimeCoreRegistryIntegration() throws {
    let registry = MF2DateTimeCore.registry()
    let dateTimeModel = try parsePublicApiModel("At {$instant :datetime dateStyle=full timeStyle=medium timeZone=UTC}")
    let dateTimeResult = try formatMessage(
        dateTimeModel,
        arguments: ["instant": .string("2026-05-21T14:30:15Z")],
        locale: "de-DE",
        functions: registry
    )
    try expectValue("date-time-core registry datetime", dateTimeResult.value, "At Donnerstag, 21. Mai 2026 um 14:30:15")
    if dateTimeResult.hasErrors {
        throw ConformanceError.expectedNoFormatErrors(
            fixture: "date-time-core registry datetime",
            actual: dateTimeResult.errors.map(\.code)
        )
    }

    let stringModel = try parsePublicApiModel("Hello {$name :string}")
    let stringResult = try formatMessage(
        stringModel,
        arguments: ["name": .string("Mojito")],
        functions: registry
    )
    try expectValue("date-time-core registry string", stringResult.value, "Hello Mojito")
    if stringResult.hasErrors {
        throw ConformanceError.expectedNoFormatErrors(
            fixture: "date-time-core registry string",
            actual: stringResult.errors.map(\.code)
        )
    }
}

private func runDateTimeCoreRegistryCases(_ cases: [DateTimeCoreRegistryCase]) throws -> Int {
    let registry = MF2DateTimeCore.registry()
    for item in cases {
        let model = try parsePublicApiModel(item.source)
        let result = try formatMessage(
            model,
            arguments: item.arguments,
            locale: item.locale,
            functions: registry
        )
        try expectValue(item.name, result.value, item.expected)
        if result.hasErrors {
            throw ConformanceError.expectedNoFormatErrors(
                fixture: item.name,
                actual: result.errors.map(\.code)
            )
        }
    }
    return cases.count
}

private func runDateTimeCoreRegistryErrorCases(_ cases: [DateTimeCoreRegistryErrorCase]) throws -> Int {
    let registry = MF2DateTimeCore.registry()
    for item in cases {
        let model = try parsePublicApiModel(item.source)
        let result = try formatMessage(
            model,
            arguments: item.arguments,
            locale: item.locale,
            functions: registry
        )
        let actual = result.errors.map(\.code)
        if actual != item.expectedErrors {
            throw ConformanceError.formatErrorMismatch(
                fixture: item.name,
                expected: item.expectedErrors.joined(separator: ","),
                actual: actual.joined(separator: ",")
            )
        }
    }
    return cases.count
}

private func runRelativeTimeCoreCheck(arguments: [String]) throws {
    let fixtureURL = resolveRelativeTimeCoreFixture(arguments: arguments)
    let data = try relativeTimeCoreData()
    let formatter = try MF2RelativeTimeCore.Formatter(data: data)
    let registry = formatter.registry()
    let fixture = try JSONDecoder().decode(
        RelativeTimeCoreFixture.self,
        from: Data(contentsOf: fixtureURL)
    )

    var checkedFormatCases = 0
    for item in fixture.cases {
        let parsed = parseToModel(item.source)
        guard let model = parsed.model, parsed.diagnostics.isEmpty else {
            throw ConformanceError.parseMismatch(
                fixture: item.label,
                expected: "valid relative-time model",
                actual: "\(String(describing: parsed.model)); diagnostics=\(parsed.diagnostics)"
            )
        }
        let actual = try formatMessage(
            model,
            arguments: item.arguments,
            locale: item.locale,
            functions: registry
        )
        if actual.value != item.expected || actual.hasErrors {
            throw ConformanceError.formatMismatch(
                fixture: item.label,
                expected: "\(item.expected); errors=[]",
                actual: "\(actual.value); errors=\(actual.errors.map(\.code))"
            )
        }
        checkedFormatCases += 1
    }

    var checkedErrorCases = 0
    for item in fixture.errorCases {
        let parsed = parseToModel(item.source)
        guard let model = parsed.model, parsed.diagnostics.isEmpty else {
            throw ConformanceError.parseMismatch(
                fixture: item.label,
                expected: "valid relative-time error model",
                actual: "\(String(describing: parsed.model)); diagnostics=\(parsed.diagnostics)"
            )
        }
        let actual = try formatMessage(
            model,
            arguments: item.arguments,
            locale: item.locale,
            functions: registry
        )
        try expectCodes("relative-time \(item.label)", actual.errors, [item.expectedError.code])
        checkedErrorCases += 1
    }

    let direct = try MF2RelativeTimeCore.format(
        .number("3600"),
        data: data,
        options: MF2RelativeTimeCore.Options(
            locale: "en",
            style: .narrow,
            numeric: .always,
            policy: .precise,
            unit: .auto
        )
    )
    try expectValue("relative-time direct API", direct, "in 1h")

    let duplicatePatternSetData = MF2RelativeTimeCore.Data(
        localeMap: ["en": "dup"],
        patternSets: [
            MF2RelativeTimeCore.PatternSet(
                id: "dup",
                data: [
                    "narrow": [
                        "hour": MF2RelativeTimeCore.UnitData(
                            future: ["one": "bad {0}h", "other": "bad {0}h"]
                        )
                    ]
                ]
            ),
            MF2RelativeTimeCore.PatternSet(
                id: "dup",
                data: [
                    "narrow": [
                        "hour": MF2RelativeTimeCore.UnitData(
                            future: ["one": "in {0}h", "other": "in {0}h"]
                        )
                    ]
                ]
            ),
        ]
    )
    let duplicatePatternSetFormatter = try MF2RelativeTimeCore.Formatter(data: duplicatePatternSetData)
    let duplicatePatternSetOutput = try duplicatePatternSetFormatter.format(
        .number("3600"),
        options: MF2RelativeTimeCore.Options(
            locale: "en",
            style: .narrow,
            numeric: .always,
            policy: .precise,
            unit: .auto
        )
    )
    try expectValue("relative-time duplicate pattern set id", duplicatePatternSetOutput, "in 1h")

    let emptyLocale = try MF2RelativeTimeCore.format(
        .number("3600"),
        data: data,
        options: MF2RelativeTimeCore.Options(
            locale: "",
            style: .narrow,
            numeric: .always,
            policy: .precise,
            unit: .auto
        )
    )
    try expectValue("relative-time direct empty locale", emptyLocale, "in 1h")

    let negativeZero = try MF2RelativeTimeCore.format(
        .number("-0"),
        data: data,
        options: MF2RelativeTimeCore.Options(
            locale: "en",
            style: .long,
            numeric: .always,
            policy: .precise,
            unit: .second
        )
    )
    try expectValue("relative-time direct negative zero", negativeZero, "0 seconds ago")

    let afterTomorrow = try MF2RelativeTimeCore.format(
        .number("172800"),
        data: data,
        options: MF2RelativeTimeCore.Options(
            locale: "fr",
            style: .long,
            numeric: .auto,
            policy: .precise,
            unit: .day
        )
    )
    try expectValue("relative-time direct after tomorrow", afterTomorrow, "après-demain")

    let parts = try MF2RelativeTimeCore.formatToParts(
        .number("-86400"),
        data: data,
        options: MF2RelativeTimeCore.Options(
            locale: "en",
            style: .long,
            numeric: .auto,
            unit: .day
        )
    )
    try expectParts("relative-time direct parts", parts, [.text("yesterday")])

    do {
        _ = try MF2RelativeTimeCore.format(
            .number("1e30"),
            data: data,
            options: MF2RelativeTimeCore.Options(
                locale: "en",
                style: .narrow,
                numeric: .always,
                policy: .precise,
                unit: .auto
            )
        )
        throw ConformanceError.expectedFormatError(fixture: "relative-time huge finite quantity", actual: "success")
    } catch let error as MF2Error {
        if error.code != "bad-operand" {
            throw ConformanceError.formatErrorMismatch(
                fixture: "relative-time huge finite quantity",
                expected: "bad-operand",
                actual: error.code
            )
        }
    }

    let referenceCases = [
        RelativeTimeReferenceCase(locale: "en", style: "long", numeric: "auto", unit: "day", seconds: -86_400),
        RelativeTimeReferenceCase(locale: "en", style: "long", numeric: "always", unit: "day", seconds: 86_400),
        RelativeTimeReferenceCase(locale: "ja", style: "narrow", numeric: "always", unit: "minute", seconds: 180),
        RelativeTimeReferenceCase(locale: "en", style: "narrow", numeric: "always", unit: "minute", seconds: -60),
        RelativeTimeReferenceCase(locale: "en", style: "long", numeric: "always", unit: "second", seconds: -0.0),
        RelativeTimeReferenceCase(locale: "fr", style: "long", numeric: "auto", unit: "day", seconds: 172_800),
    ]
    let references = try nodeIntlRelativeTimeOutputs()
    for (index, item) in referenceCases.enumerated() {
        let actual = try formatter.format(
            .number(item.seconds == 0 && item.seconds.sign == .minus ? "-0" : String(item.seconds)),
            options: MF2RelativeTimeCore.Options(
                locale: item.locale,
                style: try .fromName(item.style),
                numeric: try .fromName(item.numeric),
                policy: .precise,
                unit: try .fromName(item.unit)
            )
        )
        try expectValue("relative-time Intl reference \(index)", actual, references[index])
    }

    print(
        "Swift relative-time core test passed \(checkedFormatCases) format cases, \(checkedErrorCases) error cases, \(referenceCases.count) Intl reference cases, and direct API checks."
    )
}

private func runRelativeTimeCoreBenchmark(arguments: [String]) throws {
    let fixtureURL = resolveRelativeTimeCoreFixture(arguments: arguments)
    let iterations = Int(arguments.dropFirst().first ?? "100000") ?? 100000
    let warmupIterations = Int(arguments.dropFirst(2).first ?? "10000") ?? 10000
    let data = try relativeTimeCoreData()
    let formatter = try MF2RelativeTimeCore.Formatter(data: data)
    let fixture = try JSONDecoder().decode(
        RelativeTimeCoreFixture.self,
        from: Data(contentsOf: fixtureURL)
    )
    let cases = try fixture.cases.map { item in
        RelativeTimeCoreBenchCase(
            value: item.arguments["delta"] ?? .null,
            options: try relativeTimeOptions(locale: item.locale, source: item.source)
        )
    }
    try runNumberCoreBenchmark(
        label: "swift-relative-time-core-format",
        cases: cases,
        iterations: iterations,
        warmupIterations: warmupIterations
    ) { item in
        try formatter.format(item.value, options: item.options)
    }
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

    func expectPortable(_ label: String, source: String, expected: String) throws {
        let actual = try formatMessage(try parsePublicApiModel(source))
        try expectValue(label, actual.value, expected)
        if actual.hasErrors {
            throw ConformanceError.expectedNoFormatErrors(
                fixture: label,
                actual: actual.errors.map(\.code)
            )
        }
    }

    try expectPortable(
        "public-api huge portable number",
        source: "{1e20 :number}",
        expected: "100000000000000000000"
    )
    try expectPortable(
        "public-api huge portable percent",
        source: "{1e18 :percent}",
        expected: "100000000000000000000%"
    )
    try expectPortable(
        "public-api huge portable integer",
        source: "{1e20 :integer}",
        expected: "100000000000000000000"
    )

    func expectFoundationBadOperand(_ label: String, source: String, value: String) throws {
        let result = try formatMessage(
            try parsePublicApiModel(source),
            arguments: ["value": .string(value)],
            locale: "en-US",
            functions: .foundation
        )
        try expectCodes(label, result.errors, ["bad-operand"])
    }

    func expectFoundationBadOption(_ label: String, source: String, locale: String, value: String) throws {
        let result = try formatMessage(
            try parsePublicApiModel(source),
            arguments: ["value": .string(value)],
            locale: locale,
            functions: .foundation
        )
        try expectCodes(label, result.errors, ["bad-option"])
    }

    func expectFoundationNoErrors(_ label: String, source: String, value: String) throws {
        let result = try formatMessage(
            try parsePublicApiModel(source),
            arguments: ["value": .string(value)],
            locale: "en-US",
            functions: .foundation
        )
        if result.hasErrors {
            throw ConformanceError.expectedNoFormatErrors(
                fixture: label,
                actual: result.errors.map(\.code)
            )
        }
    }

    func expectFoundationNumber(_ label: String, locale: String, value: String, expected: String) throws {
        let result = try formatMessage(
            try parsePublicApiModel("{$value :number}"),
            arguments: ["value": .string(value)],
            locale: locale,
            functions: .foundation,
            bidiIsolation: .none
        )
        try expectValue(label, result.value, expected)
        if result.hasErrors {
            throw ConformanceError.expectedNoFormatErrors(
                fixture: label,
                actual: result.errors.map(\.code)
            )
        }
    }

    try expectFoundationBadOperand(
        "public-api foundation rejects non-ascii digit date",
        source: "{$value :date dateStyle=medium timeZone=UTC}",
        value: "٢٠٢٠-٠١-٠٢"
    )
    try expectFoundationBadOperand(
        "public-api foundation rejects unpadded date",
        source: "{$value :date dateStyle=medium timeZone=UTC}",
        value: "2020-1-2"
    )
    try expectFoundationBadOperand(
        "public-api foundation rejects impossible date",
        source: "{$value :date dateStyle=medium timeZone=UTC}",
        value: "2020-02-30"
    )
    try expectFoundationBadOperand(
        "public-api foundation rejects non-ascii digit datetime",
        source: "{$value :datetime dateStyle=medium timeStyle=medium timeZone=UTC}",
        value: "٢٠٢٠-٠١-٠٢T٠٣:٠٤:٠٥Z"
    )
    try expectFoundationBadOperand(
        "public-api foundation rejects unpadded time",
        source: "{$value :time timeStyle=medium timeZone=UTC}",
        value: "1:2"
    )
    try expectFoundationNoErrors(
        "public-api foundation accepts time-only time",
        source: "{$value :time timeStyle=medium timeZone=UTC}",
        value: "03:04:05"
    )
    try expectFoundationBadOperand(
        "public-api foundation rejects date-only time",
        source: "{$value :time timeStyle=medium timeZone=UTC}",
        value: "2020-01-02"
    )
    try expectFoundationBadOperand(
        "public-api foundation rejects time-only date",
        source: "{$value :date dateStyle=medium timeZone=UTC}",
        value: "03:04:05"
    )
    try expectFoundationBadOperand(
        "public-api foundation rejects time-only datetime",
        source: "{$value :datetime dateStyle=medium timeStyle=medium timeZone=UTC}",
        value: "03:04:05"
    )
    try expectFoundationBadOption(
        "public-api foundation rejects malformed locale",
        source: "{$value :number}",
        locale: "bad locale ???",
        value: "1"
    )
    try expectFoundationBadOption(
        "public-api foundation rejects private-use-only locale",
        source: "{$value :number}",
        locale: "x-private",
        value: "1"
    )
    try expectFoundationBadOption(
        "public-api foundation rejects oversized locale",
        source: "{$value :date dateStyle=medium timeZone=UTC}",
        locale: String(repeating: "a", count: 257),
        value: "2026-05-21"
    )
    try expectFoundationNumber(
        "public-api foundation accepts private-use extension locale",
        locale: "en-x-private",
        value: "1",
        expected: "1"
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

    for item in fixture.featureLookupChains {
        let actual = featureLookupChain(item.source, parents: item.parents)
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
    let featureLookupChains: [LocaleFeatureLookupChainCase]
}

private struct LocaleCanonicalCase: Decodable {
    let source: String
    let expected: String
}

private struct LocaleLookupChainCase: Decodable {
    let source: String
    let expected: [String]
}

private struct LocaleFeatureLookupChainCase: Decodable {
    let source: String
    let parents: [String: String]
    let expected: [String]
}

private struct NumberCoreFixture: Decodable {
    let formatCases: [NumberCoreFormatCase]
    let intlReferenceCases: [NumberCoreReferenceCase]
    let errorCases: [NumberCoreErrorCase]
    let registryCases: [NumberCoreRegistryCase]?
    let registryErrorCases: [NumberCoreRegistryErrorCase]?
}

private struct NumberCoreFormatCase: Decodable {
    let name: String
    let locale: String
    let value: MF2Value
    let options: NumberCoreOptions
    let expected: String
}

private struct NumberCoreReferenceCase: Decodable {
    let locale: String
    let value: MF2Value
    let options: NumberCoreOptions
}

private struct NumberCoreErrorCase: Decodable {
    let name: String
    let locale: String
    let value: MF2Value
    let options: NumberCoreOptions
    let expectedError: String
}

private struct NumberCoreRegistryCase: Decodable {
    let name: String
    let locale: String
    let source: String
    let arguments: [String: MF2Value]
    let expected: String
}

private struct NumberCoreRegistryErrorCase: Decodable {
    let name: String
    let locale: String
    let source: String
    let arguments: [String: MF2Value]
    let expectedErrors: [String]
}

private struct DateTimeCoreFixture: Decodable {
    let formatCases: [DateTimeCoreFormatCase]
    let intlReferenceCases: [DateTimeCoreReferenceCase]
    let semanticStyleReferenceCases: [DateTimeCoreReferenceCase]?
    let registryFormatCases: [DateTimeCoreRegistryCase]?
    let registryErrorCases: [DateTimeCoreRegistryErrorCase]?
    let errorCases: [DateTimeCoreErrorCase]
}

private struct DateTimeCoreRegistryCase: Decodable {
    let name: String
    let locale: String
    let source: String
    let arguments: [String: MF2Value]
    let expected: String
}

private struct DateTimeCoreRegistryErrorCase: Decodable {
    let name: String
    let locale: String
    let source: String
    let arguments: [String: MF2Value]
    let expectedErrors: [String]
}

private struct DateTimeCoreFormatCase: Decodable {
    let name: String
    let kind: String
    let locale: String
    let value: MF2Value
    let options: DateTimeCoreOptions
    let expected: String
}

private struct DateTimeCoreReferenceCase: Decodable {
    let name: String?
    let kind: String
    let locale: String
    let value: MF2Value
    let options: DateTimeCoreOptions
    let referenceOptions: DateTimeCoreOptions?
}

private struct DateTimeCoreErrorCase: Decodable {
    let name: String
    let kind: String
    let locale: String
    let value: MF2Value
    let options: DateTimeCoreOptions
    let expectedError: String
}

private struct RelativeTimeCoreFixture: Decodable {
    let cases: [RelativeTimeCoreFormatCase]
    let errorCases: [RelativeTimeCoreErrorCase]
}

private struct RelativeTimeCoreFormatCase: Decodable {
    let label: String
    let source: String
    let locale: String
    let arguments: [String: MF2Value]
    let expected: String
}

private struct RelativeTimeCoreErrorCase: Decodable {
    let label: String
    let source: String
    let locale: String
    let arguments: [String: MF2Value]
    let expectedError: ExpectedError
}

private struct NumberCoreOptions: Decodable, CustomStringConvertible {
    let style: String?
    let currency: String?
    let currencyDisplay: String?
    let useGrouping: Bool?
    let minimumFractionDigits: Int?
    let maximumFractionDigits: Int?
    let signDisplay: String?

    func runtimeOptions(locale: String) throws -> MF2NumberCore.Options {
        try MF2NumberCore.Options(
            locale: locale,
            style: runtimeStyle(),
            currency: currency,
            currencyDisplay: runtimeCurrencyDisplay(),
            useGrouping: useGrouping ?? true,
            minimumFractionDigits: minimumFractionDigits,
            maximumFractionDigits: maximumFractionDigits,
            signDisplay: runtimeSignDisplay()
        )
    }

    var description: String {
        [
            style.map { "style=\($0)" },
            currency.map { "currency=\($0)" },
            currencyDisplay.map { "currencyDisplay=\($0)" },
            useGrouping.map { "useGrouping=\($0)" },
            minimumFractionDigits.map { "minimumFractionDigits=\($0)" },
            maximumFractionDigits.map { "maximumFractionDigits=\($0)" },
            signDisplay.map { "signDisplay=\($0)" },
        ]
        .compactMap { $0 }
        .joined(separator: ",")
    }

    private func runtimeStyle() throws -> MF2NumberCore.Style {
        switch style ?? "number" {
        case "number":
            .number
        case "integer":
            .integer
        case "percent":
            .percent
        case "currency":
            .currency
        case let value:
            throw ConformanceError.expectedFormatError(fixture: "number-core style", actual: value)
        }
    }

    private func runtimeCurrencyDisplay() throws -> MF2NumberCore.CurrencyDisplay {
        switch currencyDisplay ?? "symbol" {
        case "symbol":
            .symbol
        case "narrowSymbol":
            .narrowSymbol
        case "code":
            .code
        case let value:
            throw ConformanceError.expectedFormatError(fixture: "number-core currencyDisplay", actual: value)
        }
    }

    private func runtimeSignDisplay() throws -> MF2NumberCore.SignDisplay {
        switch signDisplay ?? "auto" {
        case "auto":
            .auto
        case "always":
            .always
        case "never":
            .never
        case let value:
            throw ConformanceError.expectedFormatError(fixture: "number-core signDisplay", actual: value)
        }
    }
}

private struct DateTimeCoreOptions: Decodable, CustomStringConvertible {
    let style: String?
    let dateStyle: String?
    let timeStyle: String?
    let length: String?
    let precision: String?
    let dateLength: String?
    let timePrecision: String?
    let skeleton: String?
    let hourCycle: String?
    let timeZone: String?
    let calendar: String?

    func runtimeOptions(locale: String) throws -> MF2DateTimeCore.Options {
        try MF2DateTimeCore.Options(
            locale: locale,
            style: runtimeStyle(style ?? "medium"),
            dateStyle: runtimeOptionalStyle(dateStyle ?? length ?? dateLength),
            timeStyle: runtimeOptionalStyle(timeStyle ?? precision ?? timePrecision),
            skeleton: skeleton,
            hourCycle: hourCycle,
            timeZone: timeZone ?? "UTC",
            calendar: calendar
        )
    }

    var description: String {
        [
            style.map { "style=\($0)" },
            dateStyle.map { "dateStyle=\($0)" },
            timeStyle.map { "timeStyle=\($0)" },
            length.map { "length=\($0)" },
            precision.map { "precision=\($0)" },
            dateLength.map { "dateLength=\($0)" },
            timePrecision.map { "timePrecision=\($0)" },
            skeleton.map { "skeleton=\($0)" },
            hourCycle.map { "hourCycle=\($0)" },
            timeZone.map { "timeZone=\($0)" },
            calendar.map { "calendar=\($0)" },
        ]
        .compactMap { $0 }
        .joined(separator: ",")
    }

    private func runtimeOptionalStyle(_ value: String?) throws -> MF2DateTimeCore.Style? {
        guard let value else {
            return nil
        }
        return try runtimeStyle(value)
    }

    private func runtimeStyle(_ value: String) throws -> MF2DateTimeCore.Style {
        switch value {
        case "full":
            .full
        case "long":
            .long
        case "medium":
            .medium
        case "short":
            .short
        default:
            throw MF2Error.badOption("date/time style must be full, long, medium, or short.")
        }
    }
}

private struct BenchCase {
    let model: MF2Message
    let locale: String
    let arguments: [String: MF2Value]
}

private struct NumberCoreBenchCase {
    let value: MF2Value
    let options: MF2NumberCore.Options
}

private struct FoundationNumberBenchCase {
    let value: MF2Value
    let formatter: NumberFormatter
}

private struct DateTimeCoreBenchCase {
    let kind: String
    let value: MF2Value
    let options: MF2DateTimeCore.Options
}

private struct FoundationDateTimeBenchCase {
    let value: Date
    let formatter: DateFormatter
}

private struct RelativeTimeCoreBenchCase {
    let value: MF2Value
    let options: MF2RelativeTimeCore.Options
}

private struct RelativeTimeReferenceCase {
    let locale: String
    let style: String
    let numeric: String
    let unit: String
    let seconds: Double
}

private func resolveNumberCoreFixture(arguments: [String]) -> URL {
    if let first = arguments.first {
        return URL(fileURLWithPath: first).standardizedFileURL
    }
    return URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        .appendingPathComponent("../../conformance/fixtures/number-core/cases.json")
        .standardizedFileURL
}

private func resolveDateTimeCoreFixture(arguments: [String]) -> URL {
    if let first = arguments.first {
        return URL(fileURLWithPath: first).standardizedFileURL
    }
    return URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        .appendingPathComponent("../../conformance/fixtures/date-time-core/cases.json")
        .standardizedFileURL
}

private func resolveRelativeTimeCoreFixture(arguments: [String]) -> URL {
    if let first = arguments.first {
        return URL(fileURLWithPath: first).standardizedFileURL
    }
    return URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        .appendingPathComponent("../../conformance/fixtures/functions/relative-time-duration-v0.json")
        .standardizedFileURL
}

private func relativeTimeCoreData() throws -> MF2RelativeTimeCore.Data {
    let url = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        .appendingPathComponent("../../cldr/generated/relative-time/all/relative_time.json")
        .standardizedFileURL
    return try JSONDecoder().decode(MF2RelativeTimeCore.Data.self, from: Data(contentsOf: url))
}

private func relativeTimeOptions(locale: String, source: String) throws -> MF2RelativeTimeCore.Options {
    try MF2RelativeTimeCore.Options(
        locale: locale,
        style: sourceOption(source, name: "style").map(MF2RelativeTimeCore.Style.fromName) ?? .short,
        numeric: sourceOption(source, name: "numeric").map(MF2RelativeTimeCore.Numeric.fromName) ?? .always,
        policy: sourceOption(source, name: "policy").map(MF2RelativeTimeCore.Policy.fromName) ?? .precise,
        unit: sourceOption(source, name: "unit").map(MF2RelativeTimeCore.Unit.fromName) ?? .auto
    )
}

private func sourceOption(_ source: String, name: String) -> String? {
    let marker = "\(name)="
    guard let markerRange = source.range(of: marker) else {
        return nil
    }
    var end = markerRange.upperBound
    while end < source.endIndex, !source[end].isWhitespace, source[end] != "}" {
        end = source.index(after: end)
    }
    return String(source[markerRange.upperBound..<end])
}

private func nodeIntlRelativeTimeOutputs() throws -> [String] {
    let script = """
    const cases = [
      {locale:"en", style:"long", numeric:"auto", unit:"day", value:-1},
      {locale:"en", style:"long", numeric:"always", unit:"day", value:1},
      {locale:"ja", style:"narrow", numeric:"always", unit:"minute", value:3},
      {locale:"en", style:"narrow", numeric:"always", unit:"minute", value:-1},
      {locale:"en", style:"long", numeric:"always", unit:"second", value:-0},
      {locale:"fr", style:"long", numeric:"auto", unit:"day", value:2},
    ];
    process.stdout.write(JSON.stringify(cases.map((item) =>
      new Intl.RelativeTimeFormat(item.locale, { style: item.style, numeric: item.numeric }).format(item.value, item.unit)
    )));
    """
    let process = Process()
    process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
    process.arguments = ["node", "-e", script]
    let pipe = Pipe()
    process.standardOutput = pipe
    process.standardError = pipe
    try process.run()
    process.waitUntilExit()
    let outputData = pipe.fileHandleForReading.readDataToEndOfFile()
    if process.terminationStatus != 0 {
        let output = String(data: outputData, encoding: .utf8) ?? ""
        throw ConformanceError.expectedFormatError(fixture: "Node Intl relative-time reference", actual: output)
    }
    return try JSONDecoder().decode([String].self, from: outputData)
}

private func dateTimeCoreFormat(
    _ kind: String,
    value: MF2Value,
    options: MF2DateTimeCore.Options
) throws -> String {
    switch kind {
    case "date":
        try MF2DateTimeCore.formatDate(value, options: options)
    case "time":
        try MF2DateTimeCore.formatTime(value, options: options)
    case "datetime":
        try MF2DateTimeCore.formatDateTime(value, options: options)
    default:
        throw ConformanceError.expectedFormatError(fixture: "date-time-core kind", actual: kind)
    }
}

private func foundationNumberFormat(
    locale: String,
    value: MF2Value,
    options: NumberCoreOptions
) throws -> String {
    let formatter = try foundationNumberFormatter(locale: locale, options: options)
    guard let output = formatter.string(from: value.numberValue) else {
        throw ConformanceError.expectedFormatError(fixture: "Foundation number reference", actual: "nil")
    }
    return output
}

private func foundationDateTimeFormat(
    kind: String,
    locale: String,
    value: MF2Value,
    options: DateTimeCoreOptions
) throws -> String {
    let formatter = try foundationDateTimeFormatter(kind: kind, locale: locale, options: options)
    return formatter.string(from: try dateTimeCoreDate(value))
}

private func foundationDateTimeFormatter(
    kind: String,
    locale: String,
    options: DateTimeCoreOptions
) throws -> DateFormatter {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: locale.replacingOccurrences(of: "-", with: "_"))
    formatter.calendar = Calendar(identifier: .gregorian)
    formatter.timeZone = try foundationDateTimeZone(options.timeZone)
    switch kind {
    case "date":
        formatter.dateStyle = try foundationDateStyle(options.dateStyle ?? options.length ?? options.style ?? "medium")
        formatter.timeStyle = .none
    case "time":
        formatter.dateStyle = .none
        formatter.timeStyle = try foundationDateStyle(options.timeStyle ?? options.precision ?? options.style ?? "medium")
    case "datetime":
        formatter.dateStyle = try foundationDateStyle(options.dateStyle ?? options.dateLength ?? options.style ?? "medium")
        formatter.timeStyle = try foundationDateStyle(options.timeStyle ?? options.timePrecision ?? options.style ?? "medium")
    default:
        throw ConformanceError.expectedFormatError(fixture: "Foundation date/time kind", actual: kind)
    }
    return formatter
}

private func foundationDateTimeZone(_ value: String?) throws -> TimeZone {
    guard var text = value?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else {
        return TimeZone(secondsFromGMT: 0)!
    }
    if text == "UTC" || text == "Etc/UTC" || text == "Z" || text == "GMT" || text == "Etc/GMT" {
        return TimeZone(secondsFromGMT: 0)!
    }
    if (text.hasPrefix("UTC") || text.hasPrefix("GMT")) && text.count > 3 {
        text = String(text.dropFirst(3))
    }
    guard let seconds = foundationOffsetSeconds(text), let timeZone = TimeZone(secondsFromGMT: seconds) else {
        throw ConformanceError.expectedFormatError(fixture: "Foundation date/time timezone", actual: value ?? "")
    }
    return timeZone
}

private func foundationOffsetSeconds(_ value: String) -> Int? {
    guard let sign = value.first, sign == "+" || sign == "-" else {
        return nil
    }
    let pieces = value.dropFirst().split(separator: ":", omittingEmptySubsequences: false)
    guard pieces.count == 2,
          pieces[0].count == 2,
          pieces[1].count == 2,
          let hours = Int(pieces[0]),
          let minutes = Int(pieces[1]),
          hours <= 18,
          minutes < 60,
          hours < 18 || minutes == 0
    else {
        return nil
    }
    let multiplier = sign == "-" ? -1 : 1
    return multiplier * ((hours * 60 + minutes) * 60)
}

private func foundationDateStyle(_ value: String) throws -> DateFormatter.Style {
    switch value {
    case "full":
        .full
    case "long":
        .long
    case "medium":
        .medium
    case "short":
        .short
    default:
        throw ConformanceError.expectedFormatError(fixture: "Foundation date/time style", actual: value)
    }
}

private func dateTimeCoreDate(_ value: MF2Value) throws -> Date {
    switch value {
    case let .string(text), let .number(text):
        if let date = dateTimeCoreISO8601Date(text)
            ?? dateTimeCoreFixedDate(text, format: "yyyy-MM-dd'T'HH:mm:ss")
            ?? dateTimeCoreFixedDate(text, format: "yyyy-MM-dd'T'HH:mm")
            ?? dateTimeCoreFixedDate(text, format: "yyyy-MM-dd")
        {
            return date
        }
    case .bool, .null:
        break
    }
    throw ConformanceError.expectedFormatError(fixture: "date-time-core date", actual: "\(value)")
}

private func dateTimeCoreISO8601Date(_ value: String) -> Date? {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let date = formatter.date(from: value) {
        return date
    }
    formatter.formatOptions = [.withInternetDateTime]
    return formatter.date(from: value)
}

private func dateTimeCoreFixedDate(_ value: String, format: String) -> Date? {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.calendar = Calendar(identifier: .gregorian)
    formatter.timeZone = TimeZone(secondsFromGMT: 0)
    formatter.dateFormat = format
    return formatter.date(from: value)
}

private func foundationNumberFormatter(locale: String, options: NumberCoreOptions) throws -> NumberFormatter {
    let formatter = NumberFormatter()
    formatter.locale = Locale(identifier: locale.replacingOccurrences(of: "-", with: "_"))
    formatter.roundingMode = .halfUp
    switch options.style ?? "number" {
    case "number":
        formatter.numberStyle = .decimal
    case "percent":
        formatter.numberStyle = .percent
    case "currency":
        formatter.numberStyle = .currency
        formatter.currencyCode = options.currency
    case let value:
        throw ConformanceError.expectedFormatError(fixture: "Foundation number style", actual: value)
    }
    return formatter
}

private func isKnownFoundationNumberDifference(
    _ item: NumberCoreReferenceCase,
    actual: String,
    expected: String
) -> Bool {
    item.locale == "ja-JP"
        && item.options.style == "currency"
        && item.options.currency == "JPY"
        && actual == "\u{ffe5}1,235"
        && expected == "\u{a5}1,235"
}

private func isKnownFoundationDateTimeDifference(
    _ item: DateTimeCoreReferenceCase,
    actual: String,
    expected: String
) -> Bool {
    let jaFullDateSpacing = item.kind == "date"
        && item.locale == "ja-JP"
        && item.options.dateStyle == "full"
        && actual == "2026\u{5e74}5\u{6708}21\u{65e5}\u{6728}\u{66dc}\u{65e5}"
        && expected == "2026\u{5e74}5\u{6708}21\u{65e5} \u{6728}\u{66dc}\u{65e5}"
    let arFullDatePunctuation = item.kind == "date"
        && item.locale == "ar-EG"
        && item.options.dateStyle == "full"
        && actual == "\u{627}\u{644}\u{62e}\u{645}\u{64a}\u{633}\u{60c} \u{662}\u{661} \u{645}\u{627}\u{64a}\u{648} \u{662}\u{660}\u{662}\u{666}"
        && expected == "\u{627}\u{644}\u{62e}\u{645}\u{64a}\u{633}\u{60c} \u{662}\u{661} \u{645}\u{627}\u{64a}\u{648}\u{60c} \u{662}\u{660}\u{662}\u{666}"
    let arMediumTimeSpacing = item.kind == "time"
        && item.locale == "ar-EG"
        && item.options.timeStyle == "medium"
        && actual == "\u{662}:\u{663}\u{660}:\u{661}\u{665} \u{645}"
        && expected == "\u{662}:\u{663}\u{660}:\u{661}\u{665}\u{a0}\u{645}"
    let arDateTimeSpacing = item.kind == "datetime"
        && item.locale == "ar-EG"
        && item.options.dateStyle == "medium"
        && item.options.timeStyle == "short"
        && actual == "\u{662}\u{661}\u{200f}/\u{660}\u{665}\u{200f}/\u{662}\u{660}\u{662}\u{666}\u{60c} \u{662}:\u{663}\u{660} \u{645}"
        && expected == "\u{662}\u{661}\u{200f}/\u{660}\u{665}\u{200f}/\u{662}\u{660}\u{662}\u{666}\u{60c} \u{662}:\u{663}\u{660}\u{a0}\u{645}"
    let hourCycleStyle = item.options.skeleton == nil
        && (item.options.hourCycle != nil || item.referenceOptions?.hourCycle != nil)
    return jaFullDateSpacing
        || arFullDatePunctuation
        || arMediumTimeSpacing
        || arDateTimeSpacing
        || hourCycleStyle
}

private extension MF2Value {
    var numberValue: NSNumber {
        switch self {
        case let .number(value), let .string(value):
            NSNumber(value: Double(value) ?? 0)
        case let .bool(value):
            NSNumber(value: value)
        case .null:
            NSNumber(value: 0)
        }
    }
}

private func canonicalLocaleKey(_ locale: String) -> String {
    guard locale.count <= 256 else {
        return ""
    }
    return localeParts(locale).joined(separator: "-")
}

private func localeLookupChain(_ locale: String) -> [String] {
    let parts = canonicalLocaleKey(locale).split(separator: "-").map(String.init)
    return stride(from: parts.count, through: 1, by: -1)
        .map { parts.prefix($0).joined(separator: "-") }
}

private func featureLookupChain(_ locale: String, parents: [String: String]) -> [String] {
    var chain: [String] = []
    appendFeatureLookupChain(canonicalLocaleKey(locale), parents: parents, chain: &chain)
    return chain
}

private func appendFeatureLookupChain(_ locale: String, parents: [String: String], chain: inout [String]) {
    var current = locale
    while !current.isEmpty {
        if current.count > 256 { return }
        if chain.contains(current) { return }
        chain.append(current)
        if let parent = parents[current] {
            appendFeatureLookupChain(parent, parents: parents, chain: &chain)
        }
        current = structuralParent(current)
    }
}

private func structuralParent(_ locale: String) -> String {
    guard let index = locale.lastIndex(of: "-") else {
        return ""
    }
    return String(locale[..<index])
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
    if part.count == 4, part.allSatisfy(isAsciiLetter) {
        return part.prefix(1).uppercased() + part.dropFirst().lowercased()
    }
    if (part.count == 2 && part.allSatisfy(isAsciiLetter))
        || (part.count == 3 && part.allSatisfy(isAsciiDigit))
    {
        return part.uppercased()
    }
    return part.lowercased()
}

private func isAsciiLetter(_ character: Character) -> Bool {
    guard let ascii = character.asciiValue else {
        return false
    }
    return (ascii >= 65 && ascii <= 90) || (ascii >= 97 && ascii <= 122)
}

private func isAsciiDigit(_ character: Character) -> Bool {
    guard let ascii = character.asciiValue else {
        return false
    }
    return ascii >= 48 && ascii <= 57
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
        }
    }
}
