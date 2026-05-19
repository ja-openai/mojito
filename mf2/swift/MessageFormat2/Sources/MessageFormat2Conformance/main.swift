import Foundation
import MessageFormat2Runtime

do {
    let arguments = Array(CommandLine.arguments.dropFirst())
    if arguments.first == "--bench" {
        try runBenchmark(arguments: Array(arguments.dropFirst()))
        Foundation.exit(0)
    }

    let fixtureDirectory = try resolveFixtureDirectory(arguments: arguments)
    var checkedCases = 0

    for fixtureURL in try fixtureURLs(in: fixtureDirectory) {
        let fixture = try JSONDecoder().decode(
            SourceToModelFixture.self,
            from: Data(contentsOf: fixtureURL)
        )

        for formatCase in fixture.formatCases {
            let actual = try fixture.expectedModel.format(
                arguments: formatCase.arguments,
                locale: formatCase.locale
            )
            if actual != formatCase.expected {
                throw ConformanceError.formatMismatch(
                    fixture: fixtureURL.lastPathComponent,
                    expected: formatCase.expected,
                    actual: actual
                )
            }
            checkedCases += 1
        }
    }

    let checkedErrorCases = try runFormatErrorFixtures(
        fixtureRoot: fixtureDirectory.deletingLastPathComponent()
    )

    print(
        "Swift MF2 conformance runner passed \(checkedCases) format cases and \(checkedErrorCases) format error cases."
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
        _ = try benchCase.model.format(
            arguments: benchCase.arguments,
            locale: benchCase.locale
        )
    }

    let started = Date()
    var bytes = 0
    for index in 0..<iterations {
        let benchCase = cases[index % cases.count]
        let output = try benchCase.model.format(
            arguments: benchCase.arguments,
            locale: benchCase.locale
        )
        bytes += output.utf8.count
    }
    let seconds = Date().timeIntervalSince(started)
    let opsPerSecond = Double(iterations) / seconds
    print(
        "swift format iterations=\(iterations) warmup=\(warmupIterations) cases=\(cases.count) seconds=\(String(format: "%.6f", seconds)) ops_per_second=\(String(format: "%.0f", opsPerSecond)) bytes=\(bytes)"
    )
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
            throw ConformanceError.expectedFormatError(
                fixture: fixtureURL.lastPathComponent,
                actual: actual
            )
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
    let expectedModel: MF2Message
    let formatCases: [FormatCase]
}

private struct FormatCase: Decodable {
    let locale: String
    let arguments: [String: MF2Value]
    let expected: String
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

private struct BenchCase {
    let model: MF2Message
    let locale: String
    let arguments: [String: MF2Value]
}

private enum ConformanceError: Error, CustomStringConvertible {
    case noFormatCases
    case formatMismatch(fixture: String, expected: String, actual: String)
    case expectedFormatError(fixture: String, actual: String)
    case formatErrorMismatch(fixture: String, expected: String, actual: String)

    var description: String {
        switch self {
        case .noFormatCases:
            "No format cases found."
        case let .formatMismatch(fixture, expected, actual):
            "\(fixture): expected '\(expected)', got '\(actual)'"
        case let .expectedFormatError(fixture, actual):
            "\(fixture): expected format error, got '\(actual)'"
        case let .formatErrorMismatch(fixture, expected, actual):
            "\(fixture): expected error '\(expected)', got '\(actual)'"
        }
    }
}
