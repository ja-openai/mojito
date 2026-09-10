import Foundation
import MessageFormat2

func runBenchmarkRegressionChecks() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("mf2-benchmark-check-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let fixtureURL = directory.appendingPathComponent("unicode.json")
    let source = "{$x}"
    let model = try JSONSerialization.jsonObject(with: JSONEncoder().encode(parseToModel(source).model!))
    var cases: [[String: Any]] = [
        ["locale": "en", "arguments": ["x": "é😀"], "expected": "é😀"],
        ["locale": "en", "bidiIsolation": "default", "arguments": ["x": "é😀"], "expected": "\u{2068}é😀\u{2069}"],
    ]

    func writeFixture() throws {
        let fixture: [String: Any] = ["source": source, "expectedModel": model, "formatCases": cases]
        try JSONSerialization.data(withJSONObject: fixture).write(to: fixtureURL)
    }

    func benchmark(mode: String = "--bench", iterations: String, warmup: String) throws -> (Int32, String) {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: CommandLine.arguments[0])
        process.arguments = [mode, directory.path, iterations, warmup]
        let output = Pipe()
        process.standardOutput = output
        process.standardError = output
        try process.run()
        let data = output.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        return (process.terminationStatus, String(decoding: data, as: UTF8.self))
    }

    try writeFixture()
    let (status, output) = try benchmark(iterations: "3", warmup: "1")
    // Two unisolated six-byte strings and one isolated twelve-byte string.
    guard status == 0, output.contains("cases=2 "), output.contains(" bytes=24") else {
        throw BenchmarkRegressionError(message: "Benchmark fixture options/UTF-8 checksum: \(status), \(output)")
    }
    cases[1]["expected"] = "wrong"
    try writeFixture()
    let (badStatus, badOutput) = try benchmark(iterations: "1", warmup: "0")
    guard badStatus != 0, badOutput.contains("benchmark preflight"), !badOutput.contains("swift format iterations=") else {
        throw BenchmarkRegressionError(message: "Benchmark failed to preflight every case: \(badStatus), \(badOutput)")
    }

    for mode in ["--bench", "--bench-parse"] {
        for (iterations, warmup) in [("0", "0"), ("-1", "0"), ("oops", "0"), ("1", "-1")] {
            let (countStatus, countOutput) = try benchmark(mode: mode, iterations: iterations, warmup: warmup)
            guard countStatus == 1, countOutput.contains("Invalid "), !countOutput.contains("iterations=") else {
                throw BenchmarkRegressionError(message: "Invalid benchmark count did not fail normally: \(countStatus), \(countOutput)")
            }
        }
    }

    // The valid fixture intentionally omits expectedDiagnostics, meaning [].
    try JSONSerialization.data(withJSONObject: ["source": "é😀"]).write(to: fixtureURL)
    let invalidURL = directory.appendingPathComponent("z-invalid.json")
    let invalidSource = ".input {$x :string} .match $x * {{ok}} {x}"
    var invalid: [String: Any] = ["source": invalidSource, "expectedDiagnostics": [["code": "invalid-variant-key"]]]
    try JSONSerialization.data(withJSONObject: invalid).write(to: invalidURL)
    let (parseStatus, parseOutput) = try benchmark(mode: "--bench-parse", iterations: "2", warmup: "0")
    let expectedBytes = "é😀".utf8.count + invalidSource.utf8.count
    guard parseStatus == 0, parseOutput.contains("cases=2 "), parseOutput.contains("parsed=1 diagnostics=1 bytes=\(expectedBytes)") else {
        throw BenchmarkRegressionError(message: "Valid/invalid parser benchmark preflight failed: \(parseStatus), \(parseOutput)")
    }
    invalid["expectedDiagnostics"] = [["code": "wrong"]]
    try JSONSerialization.data(withJSONObject: invalid).write(to: invalidURL)
    let (wrongStatus, wrongOutput) = try benchmark(mode: "--bench-parse", iterations: "1", warmup: "0")
    guard wrongStatus == 1, wrongOutput.contains("parser benchmark preflight"), !wrongOutput.contains("swift parse iterations=") else {
        throw BenchmarkRegressionError(message: "Parser benchmark failed to preflight all diagnostics: \(wrongStatus), \(wrongOutput)")
    }
}

private struct BenchmarkRegressionError: Error, CustomStringConvertible {
    let message: String
    var description: String { message }
}
