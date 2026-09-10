package com.box.l10n.mojito.mf2

/** Bounded diagnostic benchmark with parsing outside the timer and output checks. */
object KotlinChainScalingBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        for (alias in listOf(false, true)) for (count in listOf(1000, 2000, 4000, 8000)) {
            val source = buildString {
                append(".local ${'$'}v0 = {1 :number}\n")
                for (index in 1 until count) append(".local ${'$'}v$index = {${'$'}v${index - 1}" + (if (alias) "}\n" else " :number}\n"))
                append("{{{${'$'}v${count - 1}}}}")
            }
            val parsed = Mf2Parser.parseToModel(source)
            check(!parsed.hasDiagnostics)
            val functions = Mf2FunctionRegistry.portable()
            val samples = mutableListOf<Long>()
            for (sample in -3 until 5) {
                val start = System.nanoTime()
                val result = Mf2Formatter.formatMessage(parsed.model!!, functions = functions)
                val elapsed = System.nanoTime() - start
                check(!result.hasErrors && result.value == "1")
                if (sample >= 0) samples += elapsed
            }
            println("kotlin mode=${if (alias) "alias" else "number"} declarations=$count median_ms=${samples.sorted()[2] / 1e6}")
        }
    }
}
