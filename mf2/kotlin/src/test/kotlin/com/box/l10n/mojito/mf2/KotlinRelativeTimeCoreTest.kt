package com.box.l10n.mojito.mf2

import java.nio.file.Path
import kotlin.system.exitProcess

object KotlinRelativeTimeCoreTest {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    fun run(args: Array<String>): Int {
        val fixturePath = if (args.isNotEmpty()) {
            Path.of(args[0])
        } else {
            Path.of("../conformance/fixtures/functions/relative-time-duration-v0.json")
        }
        val data = relativeTimeData()
        val fixture = KotlinJsonSupport.obj(KotlinJsonSupport.parse(fixturePath))
        val formatCases = checkFormatCases(data, KotlinJsonSupport.arrayOrEmpty(fixture["cases"]))
        val errorCases = checkErrorCases(data, KotlinJsonSupport.arrayOrEmpty(fixture["errorCases"]))
        checkDirectApi(data)
        val referenceCases = checkIntlReferenceCases(data)
        println(
            "Kotlin relative-time core test passed $formatCases format cases, " +
                "$errorCases error cases, $referenceCases Intl reference cases, and direct API checks.",
        )
        return 0
    }

    private fun checkFormatCases(
        data: Mf2RelativeTimeCore.Data,
        cases: List<Any?>,
    ): Int {
        val registry = Mf2RelativeTimeCore.registry(data)
        var checked = 0
        for (rawCase in cases) {
            val item = KotlinJsonSupport.obj(rawCase)
            val parsed = Mf2Parser.parseToModel(KotlinJsonSupport.string(item["source"]))
            if (parsed.hasDiagnostics) {
                throw AssertionError("${KotlinJsonSupport.string(item["label"])}: parse diagnostics ${parsed.diagnostics}")
            }
            val actual = Mf2Formatter.formatMessage(
                parsed.model!!,
                KotlinJsonSupport.objOrEmpty(item["arguments"]),
                locale = KotlinJsonSupport.string(item["locale"]),
                functions = registry,
            )
            val expected = KotlinJsonSupport.string(item["expected"])
            if (actual.value != expected || actual.hasErrors) {
                throw AssertionError(
                    "${KotlinJsonSupport.string(item["label"])}: expected $expected, " +
                        "got ${actual.value} errors=${actual.errors}",
                )
            }
            checked++
        }
        return checked
    }

    private fun checkErrorCases(
        data: Mf2RelativeTimeCore.Data,
        cases: List<Any?>,
    ): Int {
        val registry = Mf2RelativeTimeCore.registry(data)
        var checked = 0
        for (rawCase in cases) {
            val item = KotlinJsonSupport.obj(rawCase)
            val parsed = Mf2Parser.parseToModel(KotlinJsonSupport.string(item["source"]))
            if (parsed.hasDiagnostics) {
                throw AssertionError("${KotlinJsonSupport.string(item["label"])}: parse diagnostics ${parsed.diagnostics}")
            }
            val actual = Mf2Formatter.formatMessage(
                parsed.model!!,
                KotlinJsonSupport.objOrEmpty(item["arguments"]),
                locale = KotlinJsonSupport.string(item["locale"]),
                functions = registry,
            )
            val expected = KotlinJsonSupport.string(KotlinJsonSupport.obj(item["expectedError"])["code"])
            val actualCodes = actual.errors.map { it.code }
            if (actualCodes != listOf(expected)) {
                throw AssertionError("${KotlinJsonSupport.string(item["label"])}: expected $expected, got ${actual.errors}")
            }
            checked++
        }
        return checked
    }

    private fun checkDirectApi(data: Mf2RelativeTimeCore.Data) {
        val direct = Mf2RelativeTimeCore.format(
            3_600,
            data,
            Mf2RelativeTimeCore.Options(
                locale = "en",
                style = Mf2RelativeTimeCore.Style.NARROW,
                numeric = Mf2RelativeTimeCore.Numeric.ALWAYS,
                policy = Mf2RelativeTimeCore.Policy.PRECISE,
                unit = Mf2RelativeTimeCore.Unit.AUTO,
            ),
        )
        if (direct != "in 1h") {
            throw AssertionError("direct relative-time format expected in 1h, got $direct")
        }
        val emptyLocale = Mf2RelativeTimeCore.format(
            3_600,
            data,
            Mf2RelativeTimeCore.Options(
                locale = "",
                style = Mf2RelativeTimeCore.Style.NARROW,
                numeric = Mf2RelativeTimeCore.Numeric.ALWAYS,
                policy = Mf2RelativeTimeCore.Policy.PRECISE,
                unit = Mf2RelativeTimeCore.Unit.AUTO,
            ),
        )
        if (emptyLocale != "in 1h") {
            throw AssertionError("direct relative-time empty locale expected in 1h, got $emptyLocale")
        }
        val negativeZero = Mf2RelativeTimeCore.format(
            -0.0,
            data,
            Mf2RelativeTimeCore.Options(
                locale = "en",
                style = Mf2RelativeTimeCore.Style.LONG,
                numeric = Mf2RelativeTimeCore.Numeric.ALWAYS,
                policy = Mf2RelativeTimeCore.Policy.PRECISE,
                unit = Mf2RelativeTimeCore.Unit.SECOND,
            ),
        )
        if (negativeZero != "0 seconds ago") {
            throw AssertionError("direct relative-time negative zero expected 0 seconds ago, got $negativeZero")
        }
        val afterTomorrow = Mf2RelativeTimeCore.format(
            172_800,
            data,
            Mf2RelativeTimeCore.Options(
                locale = "fr",
                style = Mf2RelativeTimeCore.Style.LONG,
                numeric = Mf2RelativeTimeCore.Numeric.AUTO,
                policy = Mf2RelativeTimeCore.Policy.PRECISE,
                unit = Mf2RelativeTimeCore.Unit.DAY,
            ),
        )
        if (afterTomorrow != "après-demain") {
            throw AssertionError("direct relative-time after tomorrow expected après-demain, got $afterTomorrow")
        }

        val parts = Mf2RelativeTimeCore.formatToParts(
            -86_400,
            data,
            Mf2RelativeTimeCore.Options(
                locale = "en",
                style = Mf2RelativeTimeCore.Style.LONG,
                numeric = Mf2RelativeTimeCore.Numeric.AUTO,
                unit = Mf2RelativeTimeCore.Unit.DAY,
            ),
        )
        val expectedParts = listOf(mapOf("type" to "text", "value" to "yesterday"))
        if (parts != expectedParts) {
            throw AssertionError("relative-time parts expected $expectedParts, got $parts")
        }

        try {
            Mf2RelativeTimeCore.format(
                "1e30",
                data,
                Mf2RelativeTimeCore.Options(
                    locale = "en",
                    style = Mf2RelativeTimeCore.Style.NARROW,
                    numeric = Mf2RelativeTimeCore.Numeric.ALWAYS,
                    policy = Mf2RelativeTimeCore.Policy.PRECISE,
                    unit = Mf2RelativeTimeCore.Unit.AUTO,
                ),
            )
            throw AssertionError("huge relative-time quantity expected bad-operand")
        } catch (error: Mf2Error) {
            if (error.code != "bad-operand") {
                throw AssertionError("huge relative-time quantity expected bad-operand, got ${error.code}")
            }
        }
    }

    private fun checkIntlReferenceCases(data: Mf2RelativeTimeCore.Data): Int {
        val formatter = Mf2RelativeTimeCore.create(data)
        val cases = listOf(
            ReferenceCase("en", "long", "auto", "day", -86_400.0),
            ReferenceCase("en", "long", "always", "day", 86_400.0),
            ReferenceCase("ja", "narrow", "always", "minute", 180.0),
            ReferenceCase("en", "narrow", "always", "minute", -60.0),
            ReferenceCase("en", "long", "always", "second", -0.0),
            ReferenceCase("fr", "long", "auto", "day", 172_800.0),
        )
        val references = nodeIntlRelativeTimeOutputs()
        for ((index, item) in cases.withIndex()) {
            val actual = formatter.format(
                item.seconds,
                Mf2RelativeTimeCore.Options(
                    locale = item.locale,
                    style = Mf2RelativeTimeCore.Style.fromName(item.style),
                    numeric = Mf2RelativeTimeCore.Numeric.fromName(item.numeric),
                    policy = Mf2RelativeTimeCore.Policy.PRECISE,
                    unit = Mf2RelativeTimeCore.Unit.fromName(item.unit),
                ),
            )
            val expected = KotlinJsonSupport.string(references[index])
            if (actual != expected) {
                throw AssertionError("Intl relative-time reference $index: expected $expected, got $actual")
            }
        }
        return cases.size
    }

    private fun relativeTimeData(): Mf2RelativeTimeCore.Data =
        Mf2RelativeTimeCore.dataFromJson(
            KotlinJsonSupport.obj(KotlinJsonSupport.parse(Path.of("../cldr/generated/relative-time/all/relative_time.json"))),
        )

    private fun nodeIntlRelativeTimeOutputs(): List<Any?> {
        val script = """
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
        """.trimIndent()
        val process = ProcessBuilder("node", "-e", script)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) {
            throw AssertionError("Node Intl relative-time reference failed: $output")
        }
        return KotlinJsonSupport.arrayOrEmpty(KotlinJsonSupport.parse(output))
    }

    private data class ReferenceCase(
        val locale: String,
        val style: String,
        val numeric: String,
        val unit: String,
        val seconds: Double,
    )
}
