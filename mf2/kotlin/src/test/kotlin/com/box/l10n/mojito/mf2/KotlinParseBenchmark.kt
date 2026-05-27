package com.box.l10n.mojito.mf2

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

object KotlinParseBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    fun run(args: Array<String>): Int {
        val fixtureDir = if (args.isNotEmpty()) Path.of(args[0]) else Path.of("../conformance/fixtures/source-to-model")
        val iterations = args.getOrNull(1)?.toInt() ?: 100_000
        val warmupIterations = args.getOrNull(2)?.toInt() ?: 10_000
        val sources = loadSources(fixtureDir)
        if (sources.isEmpty()) {
            System.err.println("No source fixtures found.")
            return 2
        }

        repeat(warmupIterations) { index ->
            Mf2Parser.parseToModel(sources[index % sources.size])
        }

        var bytes = 0L
        var diagnostics = 0L
        var models = 0L
        val started = System.nanoTime()
        repeat(iterations) { index ->
            val source = sources[index % sources.size]
            val result = Mf2Parser.parseToModel(source)
            bytes += KotlinBenchmarkSupport.utf8Length(source)
            diagnostics += result.diagnostics.size
            if (result.model != null) {
                models++
            }
        }
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        System.out.printf(
            "kotlin parse iterations=%d warmup=%d sources=%d seconds=%.6f ops_per_second=%.0f bytes=%d diagnostics=%d models=%d%n",
            iterations,
            warmupIterations,
            sources.size,
            seconds,
            iterations / seconds,
            bytes,
            diagnostics,
            models,
        )
        return 0
    }

    private fun loadSources(fixtureDir: Path): List<String> {
        Files.list(fixtureDir).use { stream ->
            return stream
                .filter { it.fileName.toString().endsWith(".json") }
                .sorted(Comparator.comparing { it.fileName.toString() })
                .map { KotlinJsonSupport.string(KotlinJsonSupport.obj(KotlinJsonSupport.parse(it))["source"]) }
                .toList()
        }
    }
}
