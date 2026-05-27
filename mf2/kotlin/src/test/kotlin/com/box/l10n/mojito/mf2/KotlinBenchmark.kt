package com.box.l10n.mojito.mf2

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

object KotlinBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    fun run(args: Array<String>): Int {
        val fixtureDir = if (args.isNotEmpty()) Path.of(args[0]) else Path.of("../conformance/fixtures/source-to-model")
        val iterations = args.getOrNull(1)?.toInt() ?: 100_000
        val warmupIterations = args.getOrNull(2)?.toInt() ?: 10_000
        val cases = loadCases(fixtureDir)
        if (cases.isEmpty()) {
            System.err.println("No format cases found.")
            return 2
        }

        repeat(warmupIterations) { index ->
            cases[index % cases.size].format()
        }

        var bytes = 0L
        val started = System.nanoTime()
        repeat(iterations) { index ->
            val output = cases[index % cases.size].format()
            bytes += KotlinBenchmarkSupport.utf8Length(output)
        }
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        System.out.printf(
            "kotlin format iterations=%d warmup=%d cases=%d seconds=%.6f ops_per_second=%.0f bytes=%d%n",
            iterations,
            warmupIterations,
            cases.size,
            seconds,
            iterations / seconds,
            bytes,
        )
        return 0
    }

    private fun loadCases(fixtureDir: Path): List<Case> {
        val cases = mutableListOf<Case>()
        Files.list(fixtureDir).use { stream ->
            for (fixturePath in stream
                .filter { it.fileName.toString().endsWith(".json") }
                .sorted(Comparator.comparing { it.fileName.toString() })
                .toList()) {
                val fixture = KotlinJsonSupport.obj(KotlinJsonSupport.parse(fixturePath))
                val message = KotlinJsonSupport.obj(fixture["expectedModel"])
                for (rawCase in KotlinJsonSupport.arrayOrEmpty(fixture["formatCases"])) {
                    val formatCase = KotlinJsonSupport.obj(rawCase)
                    cases += Case(
                        message,
                        KotlinJsonSupport.objOrEmpty(formatCase["arguments"]),
                        KotlinJsonSupport.stringOrDefault(formatCase["locale"], "en"),
                    )
                }
            }
        }
        return cases
    }

    private data class Case(
        val message: Mf2Model,
        val arguments: Map<String, Any?>,
        val locale: String,
    ) {
        fun format(): String {
            val result = Mf2Formatter.formatMessage(message, arguments, locale)
            if (result.hasErrors) {
                throw Mf2Error("format-error", result.errors.toString())
            }
            return result.value
        }
    }
}
