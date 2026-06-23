package com.box.l10n.mojito.mf2

import java.nio.file.Path

object KotlinRelativeTimeCoreBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        val fixturePath = if (args.isNotEmpty()) {
            Path.of(args[0])
        } else {
            Path.of("../conformance/fixtures/functions/relative-time-duration-v0.json")
        }
        val iterations = args.getOrNull(1)?.toInt() ?: 100_000
        val warmupIterations = args.getOrNull(2)?.toInt() ?: 10_000
        val formatter = Mf2RelativeTimeCore.create(relativeTimeData())
        val fixture = KotlinJsonSupport.obj(KotlinJsonSupport.parse(fixturePath))
        val cases = KotlinJsonSupport.arrayOrEmpty(fixture["cases"])
            .map(::relativeTimeCoreCase)
        if (cases.isEmpty()) {
            throw IllegalArgumentException("No relative-time-core cases found.")
        }

        repeat(warmupIterations) { index ->
            cases[index % cases.size].format(formatter)
        }

        var bytes = 0L
        val started = System.nanoTime()
        repeat(iterations) { index ->
            bytes += KotlinBenchmarkSupport.utf8Length(cases[index % cases.size].format(formatter))
        }
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        System.out.printf(
            "%s iterations=%d warmup=%d cases=%d seconds=%.6f ns_per_op=%.1f ops_per_second=%.0f bytes=%d%n",
            "kotlin-relative-time-core-format",
            iterations,
            warmupIterations,
            cases.size,
            seconds,
            seconds * 1_000_000_000.0 / iterations,
            iterations / seconds,
            bytes,
        )
    }

    private fun relativeTimeCoreCase(rawCase: Any?): RelativeTimeCoreCase {
        val item = KotlinJsonSupport.obj(rawCase)
        return RelativeTimeCoreCase(
            value = KotlinJsonSupport.obj(item["arguments"])["delta"],
            options = options(
                KotlinJsonSupport.string(item["locale"]),
                KotlinJsonSupport.string(item["source"]),
            ),
        )
    }

    private fun options(locale: String, source: String): Mf2RelativeTimeCore.Options {
        val builder = Mf2RelativeTimeCore.options().locale(locale)
        sourceOption(source, "style")?.let { builder.style(Mf2RelativeTimeCore.Style.fromName(it)) }
        sourceOption(source, "numeric")?.let { builder.numeric(Mf2RelativeTimeCore.Numeric.fromName(it)) }
        sourceOption(source, "policy")?.let { builder.policy(Mf2RelativeTimeCore.Policy.fromName(it)) }
        sourceOption(source, "unit")?.let { builder.unit(Mf2RelativeTimeCore.Unit.fromName(it)) }
        return builder.build()
    }

    private fun sourceOption(source: String, name: String): String? {
        val marker = "$name="
        val start = source.indexOf(marker).takeIf { it >= 0 }?.let { it + marker.length } ?: return null
        val end = generateSequence(start) { it + 1 }
            .first { it >= source.length || source[it].isWhitespace() || source[it] == '}' }
        return source.substring(start, end)
    }

    private fun relativeTimeData(): Mf2RelativeTimeCore.Data =
        Mf2RelativeTimeCore.dataFromJson(
            KotlinJsonSupport.obj(KotlinJsonSupport.parse(Path.of("../cldr/generated/relative-time/all/relative_time.json"))),
        )

    private data class RelativeTimeCoreCase(
        val value: Any?,
        val options: Mf2RelativeTimeCore.Options,
    ) {
        fun format(formatter: Mf2RelativeTimeCore.Formatter): String =
            formatter.format(value, options)
    }
}
