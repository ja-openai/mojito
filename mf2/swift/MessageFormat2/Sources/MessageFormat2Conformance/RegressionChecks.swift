import Foundation
import MessageFormat2

func runRuntimeRegressionChecks() throws {
    let checks = RegressionChecks()
    try checks.testMalformedVariantTerminatesAndQuotedBracesParse()
    try checks.testExactDecimalFormattingAndSelection()
    try checks.testNumericBoundsRecoverWithoutTrapping()
    try checks.testModelConstructionValidationAndRoundTrip()
    try checks.testDirectionFormattingPartsAndInheritance()
    try checks.testUnicodeDirectionValuesAndInvalidIgnore()
    try checks.testDefaultNumericIsolationMetadata()
    try checks.testLongDeclarationChains()
    try checks.testLongDateSourceAndQuotedVariants()
}

private struct RegressionChecks {
    private func render(
        _ source: String, value: String = "", locale: String = "en", registry: MF2FunctionRegistry = .defaults
    ) throws -> MF2FormatResult {
        let parsed = parseToModel(source)
        let model = try require(parsed.model, "\(parsed.diagnostics)")
        return try model.format(arguments: ["x": .number(value)], locale: locale, functions: registry)
    }

    func testMalformedVariantTerminatesAndQuotedBracesParse() throws {
        for source in [".input {$x :string} .match $x * {{ok}} {x}", ".input {$x :string} .match $x * {{ok}} {}"] {
            try check(
                parseToModel(source).diagnostics.contains { $0.code == "invalid-variant-key" },
                "\(parseToModel(source).diagnostics)")
        }
        try checkEqual(try render("{{{|{|}}}").value, "{")
        try checkEqual(try render("{{{|}|}}}").value, "}")
    }

    func testExactDecimalFormattingAndSelection() throws {
        for (source, value, expected) in [
            ("{$x :offset add=0}", "0.29", "0.29"),
            ("{$x :offset add=0}", "-0.29", "-0.29"),
            ("{$x :percent}", "0.29", "29%"), ("{$x :percent}", "0.07", "7%"),
            ("{$x :number}", "9007199254740993", "9007199254740993"),
            ("{$x :integer}", "1e20", "100000000000000000000"),
            ("{$x :number maximumFractionDigits=2}", "1.235", "1.24"),
            ("{$x :number maximumFractionDigits=2}", "1.245", "1.24"),
            ("{$x :offset add=1}", "9007199254740993.1", "9007199254740994.1"),
        ] {
            let output = try render(source, value: value)
            try checkEqual(output.value, expected)
            try check(output.ok, "\(output.errors)")
        }
        try checkEqual(
            try render(
                ".input {$x :percent} .match $x many {{many}} other {{other}} * {{fallback}}", value: "0.29",
                locale: "ru"
            ).value, "many")
        let exact = try render(
            ".input {$x :number select=exact} .match $x 9007199254740993 {{exact}} * {{fallback}}",
            value: "9007199254740993")
        try checkEqual(exact.value, "exact")
        try check(exact.ok)
    }

    func testLongDeclarationChains() throws {
        for annotated in [false, true] {
            var source = ".input {$x :number minimumFractionDigits=2 u:dir=ltr}\n"
            var previous = "x"
            for index in 0..<7000 {
                let name = "v\(index)"
                let annotation = annotated ? " :offset add=1" : ""
                source += ".local $\(name) = {$\(previous)\(annotation)}\n"
                previous = name
            }
            source += annotated ? "{{{$\(previous) :number}}}" : ".match $\(previous)\none {{one}}\n* {{other}}"
            let result = try render(source, value: "1")
            try checkEqual(result.value, annotated ? "7001.00" : "other")
            try check(result.ok, "\(result.errors)")
        }
    }
    func testLongDateSourceAndQuotedVariants() throws {
        var source = ".local $v = {$x :date dateStyle=long timeZone=UTC}\n"
        var previous = "v"
        for index in 0..<7000 {
            let name = "v\(index)"
            source += ".local $\(name) = {$\(previous) :string}\n"
            previous = name
        }
        source += "{{{$\(previous) :date dateStyle=long timeZone=UTC}}}"
        let model = try require(parseToModel(source).model)
        let result = try model.format(arguments: ["x": .string("2026-05-21")], functions: .foundation)
        try check(result.ok, "\(result.errors)")
        try check(result.value.contains("2026"))
        var variants = ".input {$x :string} .match $x\n"
        for index in 0..<1000 { variants += "|v\(index)| {{value \(index)}}\n" }
        variants += "* {{fallback}}"
        let variantModel = try require(parseToModel(variants).model)
        try checkEqual(try variantModel.format(arguments: ["x": .string("v999")]).value, "value 999")
    }

    func testNumericBoundsRecoverWithoutTrapping() throws {
        for value in ["1e-2147483648", "1e-400", "1e4096", "1e99999999999999999"] {
            try check(
                try render("{$x :number}", value: value, registry: .foundation).errors.contains {
                    $0.code == "bad-operand"
                })
        }
        try check(
            try render(".local $n = {$x :offset add=0} {{{$n :number}}}", value: "1e-400", registry: .foundation)
                .errors.contains { $0.code == "bad-operand" })
        for value in [String(Int.max), String(Int.min)] {
            try check(
                try render("{$x :relativeTime unit=week}", value: value, registry: .foundation).errors.contains {
                    $0.code == "bad-operand"
                })
        }
        try checkThrows(try JSONDecoder().decode(MF2Value.self, from: Data("1e20".utf8)))
        let decoded = try JSONDecoder().decode(MF2Value.self, from: Data("18446744073709551615".utf8))
        let model = try require(parseToModel("{$x :number}").model)
        try checkEqual(try model.format(arguments: ["x": decoded]).value, "18446744073709551615")
        try checkEqual(try render("{$x :number}", value: "1e20").value, "100000000000000000000")
        try check(
            try render(".input {$x :number} {{{$x :relativeTime unit=day}}}", value: "1e20", registry: .foundation)
                .errors.contains { $0.code == "bad-operand" })
        try check(try render("{$x :number}", value: "1e4096").errors.contains { $0.code == "bad-operand" })
        try check(
            try render("{$x :number minimumFractionDigits=3 maximumFractionDigits=2}", value: "1").errors.contains {
                $0.code == "bad-option"
            })
        try check(
            try render("{$x :number minimumFractionDigits=1001}", value: "1").errors.contains {
                $0.code == "bad-option"
            })
        let plural = try render(".input {$x :number} .match $x one {{one}} * {{fallback}}", value: "1e20")
        try check(plural.errors.contains { $0.code == "bad-selector" })
    }

    func testModelConstructionValidationAndRoundTrip() throws {
        let expression = MF2Expression(
            arg: .variable("x"), function: MF2Function(name: "number"), attributes: ["id": .literal(.literal("count"))])
        let model = MF2Message.message(
            declarations: [], pattern: [.expression(expression), .markup(MF2Markup(kind: "standalone", name: "br"))])
        try checkEqual(try JSONDecoder().decode(MF2Message.self, from: JSONEncoder().encode(model)), model)
        for invalid in [
            MF2Expression(), MF2Expression(arg: .literal("x"), attributes: ["a": .present(false)]),
            MF2Expression(arg: .literal("x"), attributes: ["a": .literal(.variable("x"))]),
        ] {
            try checkThrows(try MF2Message.message(declarations: [], pattern: [.expression(invalid)]).format()) {
                error in
                try checkEqual((error as? MF2Error)?.code, "invalid-model")
            }
        }
        try checkThrows(
            try JSONDecoder().decode(MF2Function.self, from: Data(#"{"type":"variable","name":"number"}"#.utf8)))
    }

    func testDefaultNumericIsolationMetadata() throws {
        let model = try require(parseToModel("{$x :number}").model)
        for (locale, expected) in [
            ("en", "1"), ("fr", "1"), ("az-IR", "\u{2068}1\u{2069}"), ("az-Latn-IR", "1"), ("sd-IN", "1"),
            ("und", "\u{2068}1\u{2069}"), ("xx-XXXX", "\u{2068}1\u{2069}"),
        ] {
            try checkEqual(
                try model.format(arguments: ["x": .number("1")], locale: locale, bidiIsolation: .default).value,
                expected)
        }
        let custom = MF2FunctionRegistry.portable.withFunction("number") { $0.value }
        try checkEqual(
            try model.format(arguments: ["x": .number("1")], functions: custom, bidiIsolation: .default).value,
            "\u{2068}1\u{2069}")
        let explicit = try require(parseToModel(".input {$x :number u:dir=ltr} {{{$x}}}").model)
        try checkEqual(
            try explicit.format(arguments: ["x": .number("1")], bidiIsolation: .default).value, "\u{2066}1\u{2069}")
    }

    func testUnicodeDirectionValuesAndInvalidIgnore() throws {
        for (source, codes) in [
            ("{$x :number u:dir=invalid}", ["bad-option"]),
            ("{$x :number u:dir=$direction}", ["unresolved-variable", "bad-option"]),
        ] {
            let result = try render(source, value: "1")
            try checkEqual(result.value, "1")
            try checkEqual(result.errors.map(\.code), codes)
        }
        for (direction, expected) in [
            ("ltr", "\u{2066}1\u{2069}"), ("rtl", "\u{2067}1\u{2069}"),
            ("auto", "\u{2068}1\u{2069}"), ("inherit", "1"),
        ] {
            let model = try require(parseToModel("{$x :number u:dir=$direction}").model)
            let result = try model.format(
                arguments: ["x": .number("1"), "direction": .string(direction)], bidiIsolation: .default)
            try checkEqual(result.value, expected)
            try check(result.ok)
        }
        for annotation in [":string", ":string u:dir=inherit", ":string u:dir=invalid"] {
            let model = try require(parseToModel(".input {$x :number u:dir=ltr} {{{$x \(annotation)}}}").model)
            try checkEqual(try model.format(arguments: ["x": .number("1")], bidiIsolation: .default).value, "1")
        }
        let registry = MF2FunctionRegistry.portable.withFunction("check") { call in
            try check(call.optionValue("u:dir") == nil)
            return call.value
        }
        let model = try require(parseToModel("{$x :check u:dir=rtl}").model)
        try checkEqual(
            try model.format(arguments: ["x": .string("ok")], functions: registry, bidiIsolation: .default).value,
            "\u{2067}ok\u{2069}")
    }

    func testDirectionFormattingPartsAndInheritance() throws {
        let model = try require(parseToModel(".input {$x :string u:dir=rtl} {{{$x}}}").model)
        let result = try model.format(arguments: ["x": .string("hello")], bidiIsolation: .default)
        try checkEqual(result.value, "\u{2067}hello\u{2069}")
        try checkEqual(
            try model.formatToParts(arguments: ["x": .string("hello")]).parts,
            [.expression("hello", attributes: [:], direction: "rtl")])
        try check(try render("{$x :string u:dir=bad}", value: "hello").errors.contains { $0.code == "bad-option" })
    }
}

private enum RegressionFailure: Error { case failed(String) }
private func require<T>(_ value: T?, _ message: String = "Expected a value") throws -> T {
    guard let value else { throw RegressionFailure.failed(message) }
    return value
}
private func check(_ value: Bool, _ message: String = "Expected true", line: Int = #line) throws {
    if !value { throw RegressionFailure.failed("\(message) at line \(line)") }
}
private func checkEqual<T: Equatable>(_ actual: T, _ expected: T) throws {
    if actual != expected { throw RegressionFailure.failed("Expected \(expected), got \(actual)") }
}
private func checkThrows<T>(_ value: @autoclosure () throws -> T, verify: ((Error) throws -> Void)? = nil) throws {
    do { _ = try value() } catch {
        try verify?(error)
        return
    }
    throw RegressionFailure.failed("Expected an error")
}