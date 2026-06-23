package com.box.l10n.mojito.mf2

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

object KotlinConformance {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    fun run(args: Array<String>): Int {
        val fixtureDir = if (args.isNotEmpty()) {
            Path.of(args[0])
        } else {
            Path.of("../conformance/fixtures/source-to-model")
        }

        var checkedCases = 0
        var checkedPartsCases = 0
        var checkedFallbackCases = 0
        var checkedFallbackPartsCases = 0
        var checkedModels = 0
        val conformanceFunctions = Mf2FunctionRegistry.portable()

        for (fixturePath in jsonFiles(fixtureDir)) {
            val fixture = KotlinJsonSupport.obj(KotlinJsonSupport.parse(fixturePath))
            val expectedModel = KotlinJsonSupport.obj(fixture["expectedModel"])
            val parseResult = Mf2Parser.parseToModel(KotlinJsonSupport.string(fixture["source"]))
            if (parseResult.hasDiagnostics) {
                System.err.printf(
                    "%s: expected parse success, got diagnostics %s%n",
                    fixturePath.fileName,
                    parseResult.diagnostics,
                )
                return 1
            }
            if (expectedModel != parseResult.model) {
                System.err.printf("%s: parsed model did not match expected model%n", fixturePath.fileName)
                return 1
            }
            checkedModels++

            for (rawCase in KotlinJsonSupport.arrayOrEmpty(fixture["formatCases"])) {
                val formatCase = KotlinJsonSupport.obj(rawCase)
                val actual = Mf2Formatter.formatMessage(
                    parseResult.model!!,
                    KotlinJsonSupport.objOrEmpty(formatCase["arguments"]),
                    KotlinJsonSupport.stringOrDefault(formatCase["locale"], "en"),
                    Mf2BidiIsolation.fromName(
                        KotlinJsonSupport.stringOrDefault(formatCase["bidiIsolation"], "none"),
                    ),
                    functions = conformanceFunctions,
                )
                val expected = KotlinJsonSupport.string(formatCase["expected"])
                if (actual.value != expected || actual.errors.isNotEmpty()) {
                    System.err.printf(
                        "%s: expected %s, got %s errors=%s%n",
                        fixturePath.fileName,
                        expected,
                        actual.value,
                        actual.errors,
                    )
                    return 1
                }
                checkedCases++
            }

            for (rawCase in KotlinJsonSupport.arrayOrEmpty(fixture["partsCases"])) {
                val partsCase = KotlinJsonSupport.obj(rawCase)
                val result = Mf2Formatter.formatMessageToParts(
                    parseResult.model!!,
                    KotlinJsonSupport.objOrEmpty(partsCase["arguments"]),
                    KotlinJsonSupport.stringOrDefault(partsCase["locale"], "en"),
                    functions = conformanceFunctions,
                )
                val actual = KotlinFormattedPartJson.toMaps(result.parts)
                val expected = KotlinJsonSupport.arrayOrEmpty(partsCase["expected"])
                    .map(KotlinJsonSupport::obj)
                if (actual != expected) {
                    System.err.printf("%s: expected parts %s, got %s%n", fixturePath.fileName, expected, actual)
                    return 1
                }
                if (result.errors.isNotEmpty()) {
                    System.err.printf("%s: expected no parts errors, got %s%n", fixturePath.fileName, result.errors)
                    return 1
                }
                checkedPartsCases++
            }

            for (rawCase in KotlinJsonSupport.arrayOrEmpty(fixture["fallbackCases"])) {
                val fallbackCase = KotlinJsonSupport.obj(rawCase)
                val actual = Mf2Formatter.formatMessage(
                    parseResult.model!!,
                    KotlinJsonSupport.objOrEmpty(fallbackCase["arguments"]),
                    KotlinJsonSupport.stringOrDefault(fallbackCase["locale"], "en"),
                    functions = conformanceFunctions,
                )
                val expected = KotlinJsonSupport.string(fallbackCase["expected"])
                if (actual.value != expected) {
                    System.err.printf(
                        "%s: expected fallback %s, got %s%n",
                        fixturePath.fileName,
                        expected,
                        actual.value,
                    )
                    return 1
                }
                assertErrorCodes(fixturePath, "fallback errors", actual.errors, fallbackCase)
                checkedFallbackCases++
            }

            for (rawCase in KotlinJsonSupport.arrayOrEmpty(fixture["fallbackPartsCases"])) {
                val partsCase = KotlinJsonSupport.obj(rawCase)
                val result = Mf2Formatter.formatMessageToParts(
                    parseResult.model!!,
                    KotlinJsonSupport.objOrEmpty(partsCase["arguments"]),
                    KotlinJsonSupport.stringOrDefault(partsCase["locale"], "en"),
                    functions = conformanceFunctions,
                )
                val actual = KotlinFormattedPartJson.toMaps(result.parts)
                val expected = KotlinJsonSupport.arrayOrEmpty(partsCase["expected"])
                    .map(KotlinJsonSupport::obj)
                if (actual != expected) {
                    System.err.printf("%s: expected fallback parts %s, got %s%n", fixturePath.fileName, expected, actual)
                    return 1
                }
                assertErrorCodes(fixturePath, "fallback parts errors", result.errors, partsCase)
                checkedFallbackPartsCases++
            }
        }

        val checkedInvalidSources = checkInvalidSourceFixtures(fixtureDir.parent)
        val checkedErrorCases = checkFormatErrorFixtures(fixtureDir.parent)
        val checkedLocaleKeyCases = checkLocaleKeyFixtures(fixtureDir.parent)
        checkPortableDecimalOperandBounds()
        checkPublicApiEdgeCases()
        System.out.printf(
            "Kotlin MF2 conformance runner passed %d source models, %d format cases, %d parts cases, " +
                "%d fallback cases, %d fallback parts cases, %d invalid source cases, " +
                "%d format error cases, and %d locale key cases.%n",
            checkedModels,
            checkedCases,
            checkedPartsCases,
            checkedFallbackCases,
            checkedFallbackPartsCases,
            checkedInvalidSources,
            checkedErrorCases,
            checkedLocaleKeyCases,
        )
        return 0
    }

    private fun checkPortableDecimalOperandBounds() {
        val boundary = Mf2FunctionCall(
            value = "1e1000000",
            rawValue = "1e1000000",
            function = mapOf("name" to "number"),
            locale = "en",
            optionResolver = { _, fallback -> fallback },
            inheritedSource = null,
        )
        Mf2PortableFunctions.parseCallDecimalOperand(boundary, "Number function requires a numeric operand.")

        for (value in listOf("1e1000001", "1e10000000")) {
            val call = Mf2FunctionCall(
                value = value,
                rawValue = value,
                function = mapOf("name" to "number"),
                locale = "en",
                optionResolver = { _, fallback -> fallback },
                inheritedSource = null,
            )
            try {
                Mf2PortableFunctions.parseCallDecimalOperand(call, "Number function requires a numeric operand.")
                throw ConformanceFailure("portable decimal operand should reject exponent in $value")
            } catch (error: Mf2Error) {
                if (error.code != "bad-operand") {
                    throw ConformanceFailure("portable decimal operand expected bad-operand for $value, got ${error.code}")
                }
            }
        }
    }

    private fun checkInvalidSourceFixtures(fixtureRoot: Path): Int {
        val fixtureDir = fixtureRoot.resolve("invalid-source")
        if (!Files.isDirectory(fixtureDir)) {
            return 0
        }

        var checkedCases = 0
        for (fixturePath in jsonFiles(fixtureDir)) {
            val fixture = KotlinJsonSupport.obj(KotlinJsonSupport.parse(fixturePath))
            val parseResult = Mf2Parser.parseToModel(KotlinJsonSupport.string(fixture["source"]))
            val actualCodes = parseResult.diagnostics.map { it.code }
            val expectedCodes = KotlinJsonSupport.arrayOrEmpty(fixture["expectedDiagnostics"])
                .map(KotlinJsonSupport::obj)
                .map { KotlinJsonSupport.string(it["code"]) }
            if (actualCodes != expectedCodes) {
                throw ConformanceFailure("${fixturePath.fileName}: expected diagnostics $expectedCodes, got $actualCodes")
            }
            checkedCases++
        }
        return checkedCases
    }

    private fun checkFormatErrorFixtures(fixtureRoot: Path): Int {
        val fixtureDir = fixtureRoot.resolve("format-errors")
        if (!Files.isDirectory(fixtureDir)) {
            return 0
        }

        var checkedCases = 0
        for (fixturePath in jsonFiles(fixtureDir)) {
            val fixture = KotlinJsonSupport.obj(KotlinJsonSupport.parse(fixturePath))
            val message = KotlinJsonSupport.obj(fixture["model"])
            val expectedCode = KotlinJsonSupport.string(
                KotlinJsonSupport.obj(fixture["expectedError"])["code"],
            )
            try {
                val actual = Mf2Formatter.formatMessage(
                    message,
                    KotlinJsonSupport.objOrEmpty(fixture["arguments"]),
                    KotlinJsonSupport.stringOrDefault(fixture["locale"], "en"),
                )
                if (actual.errors.none { it.code == expectedCode }) {
                    throw ConformanceFailure("${fixturePath.fileName}: expected error $expectedCode, got ${actual.errors}")
                }
            } catch (error: Mf2Error) {
                if (error.code != expectedCode) {
                    throw ConformanceFailure("${fixturePath.fileName}: expected error $expectedCode, got ${error.code}")
                }
            }
            checkedCases++
        }
        return checkedCases
    }

    private fun checkLocaleKeyFixtures(fixtureRoot: Path): Int {
        val fixturePath = fixtureRoot.resolve("locale-key").resolve("cases.json")
        if (!Files.isRegularFile(fixturePath)) {
            return 0
        }

        var checkedCases = 0
        val fixture = KotlinJsonSupport.obj(KotlinJsonSupport.parse(fixturePath))
        for (rawCase in KotlinJsonSupport.arrayOrEmpty(fixture["canonical"])) {
            val item = KotlinJsonSupport.obj(rawCase)
            val actual = LocaleKey.canonicalKey(KotlinJsonSupport.string(item["source"]))
            val expected = KotlinJsonSupport.string(item["expected"])
            if (actual != expected) {
                throw ConformanceFailure("${fixturePath.fileName}: expected canonical $expected, got $actual")
            }
            checkedCases++
        }
        for (rawCase in KotlinJsonSupport.arrayOrEmpty(fixture["lookupChains"])) {
            val item = KotlinJsonSupport.obj(rawCase)
            val actual = LocaleKey.lookupChain(KotlinJsonSupport.string(item["source"]))
            val expected = KotlinJsonSupport.arrayOrEmpty(item["expected"]).map(KotlinJsonSupport::string)
            if (actual != expected) {
                throw ConformanceFailure("${fixturePath.fileName}: expected lookup chain $expected, got $actual")
            }
            checkedCases++
        }
        for (rawCase in KotlinJsonSupport.arrayOrEmpty(fixture["featureLookupChains"])) {
            val item = KotlinJsonSupport.obj(rawCase)
            val parents = KotlinJsonSupport.objOrEmpty(item["parents"]).mapValues { KotlinJsonSupport.string(it.value) }
            val actual = LocaleKey.featureLookupChain(KotlinJsonSupport.string(item["source"]), parents)
            val expected = KotlinJsonSupport.arrayOrEmpty(item["expected"]).map(KotlinJsonSupport::string)
            if (actual != expected) {
                throw ConformanceFailure("${fixturePath.fileName}: expected feature lookup chain $expected, got $actual")
            }
            checkedCases++
        }
        return checkedCases
    }

    private fun checkPublicApiEdgeCases() {
        val message = parsePublicApiModel("Hello {${'$'}name}")
        val emptyMissing = Mf2Formatter.formatMessage(
            model = message,
            onMissingArgument = { "" },
        )
        assertPublicApiValue("empty missing recovery", "Hello ", emptyMissing.value)
        assertPublicApiCodes("empty missing errors", listOf("unresolved-variable"), emptyMissing.errors)

        val emptyMissingParts = Mf2Formatter.formatMessageToParts(
            model = message,
            onMissingArgument = { "" },
        )
        assertPublicApiParts(
            "empty missing parts",
            listOf(
                mapOf("type" to "text", "value" to "Hello "),
                mapOf("type" to "fallback", "source" to "\$name", "value" to ""),
            ),
            KotlinFormattedPartJson.toMaps(emptyMissingParts.parts),
        )

        val declinedMissing = Mf2Formatter.formatMessage(
            model = message,
            onMissingArgument = { null },
        )
        assertPublicApiValue("declined missing recovery", "Hello {${'$'}name}", declinedMissing.value)

        val declinedMissingParts = Mf2Formatter.formatMessageToParts(
            model = message,
            onMissingArgument = { null },
        )
        assertPublicApiParts(
            "declined missing parts",
            listOf(
                mapOf("type" to "text", "value" to "Hello "),
                mapOf("type" to "fallback", "source" to "\$name"),
            ),
            KotlinFormattedPartJson.toMaps(declinedMissingParts.parts),
        )

        val integerMessage = parsePublicApiModel("Hello {${'$'}name :integer}")
        val emptyFormatError = Mf2Formatter.formatMessage(
            model = integerMessage,
            arguments = mapOf("name" to "abc"),
            onFormatError = { "" },
        )
        assertPublicApiValue("empty format-error recovery", "Hello ", emptyFormatError.value)
        assertPublicApiCodes("empty format-error errors", listOf("bad-operand"), emptyFormatError.errors)

        val emptyFormatErrorParts = Mf2Formatter.formatMessageToParts(
            model = integerMessage,
            arguments = mapOf("name" to "abc"),
            onFormatError = { "" },
        )
        assertPublicApiParts(
            "empty format-error parts",
            listOf(
                mapOf("type" to "text", "value" to "Hello "),
                mapOf("type" to "fallback", "source" to "\$name", "value" to ""),
            ),
            KotlinFormattedPartJson.toMaps(emptyFormatErrorParts.parts),
        )
    }

    private fun parsePublicApiModel(source: String): Mf2Model {
        val result = Mf2Parser.parseToModel(source)
        if (result.hasDiagnostics || result.model == null) {
            throw ConformanceFailure("public-api: expected valid source, got ${result.diagnostics}")
        }
        return result.model
    }

    private fun assertPublicApiValue(label: String, expected: String, actual: String) {
        if (actual != expected) {
            throw ConformanceFailure("public-api $label: expected $expected, got $actual")
        }
    }

    private fun assertPublicApiCodes(label: String, expected: List<String>, actualErrors: List<Mf2Error>) {
        val actual = actualErrors.map { it.code }
        if (actual != expected) {
            throw ConformanceFailure("public-api $label: expected $expected, got $actual")
        }
    }

    private fun assertPublicApiParts(
        label: String,
        expected: List<Map<String, Any?>>,
        actual: List<Map<String, Any?>>,
    ) {
        if (actual != expected) {
            throw ConformanceFailure("public-api $label: expected $expected, got $actual")
        }
    }

    private fun jsonFiles(dir: Path): List<Path> =
        Files.list(dir).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".json") }
                .sorted(Comparator.comparing { it.fileName.toString() })
                .toList()
        }

    private fun assertErrorCodes(
        fixturePath: Path,
        label: String,
        actualErrors: List<Mf2Error>,
        item: Map<String, Any?>,
    ) {
        val actualCodes = actualErrors.map { it.code }
        val expectedCodes = KotlinJsonSupport.arrayOrEmpty(item["expectedErrors"])
            .map(KotlinJsonSupport::obj)
            .map { KotlinJsonSupport.string(it["code"]) }
        if (actualCodes != expectedCodes) {
            throw ConformanceFailure("${fixturePath.fileName}: expected $label $expectedCodes, got $actualCodes")
        }
    }

    private class ConformanceFailure(message: String) : RuntimeException(message)
}
