package com.box.l10n.mojito.mf2

object KotlinTranslateDemo {
    @JvmStatic
    fun main(args: Array<String>) {
        val source =
            ".input {${'$'}count :number select=cardinal} .match ${'$'}count one {{You have one task.}} * {{You have {${'$'}count} tasks.}}"
        val result = Mf2Parser.parseToModel(source)
        if (result.hasDiagnostics || result.model == null) {
            error("Parse failed: ${result.diagnostics}")
        }

        val message = result.model ?: error("Missing parsed model.")
        println(formatOrThrow(message, mapOf("count" to 1), "en"))
        println(formatOrThrow(message, mapOf("count" to 5), "en"))

        val recoveryModel = Mf2Parser.parseToModel("Hello {${'$'}name}").model
            ?: error("Missing recovery model.")
        val recovered = Mf2Formatter.formatMessage(
            model = recoveryModel,
            locale = "en",
            onMissingArgument = { context -> "[missing ${context.variableName}]" },
        )
        if (recovered.value != "Hello [missing name]" || recovered.errors.size != 1) {
            error("Unexpected recovery result: $recovered")
        }
        println(recovered.value)

        val unsupportedFunctionModel = Mf2Parser.parseToModel("Total: {${'$'}amount :currency currency=USD}").model
            ?: error("Missing unsupported function model.")
        val unsupportedFunctionResult = Mf2Formatter.formatMessage(
            model = unsupportedFunctionModel,
            arguments = mapOf("amount" to 42),
            locale = "en",
            functions = Mf2FunctionRegistry.portable(),
        )
        if (!unsupportedFunctionResult.hasErrors || unsupportedFunctionResult.value != "Total: {${'$'}amount}") {
            error("Unexpected unsupported function result: $unsupportedFunctionResult")
        }

        val partsSource = "Review {${'$'}name} before {${'$'}deadline}."
        val parts = Mf2Parser.parseToModel(partsSource).model
            ?.let {
                val result = Mf2Formatter.formatMessageToParts(
                    it,
                    mapOf("name" to "checkout", "deadline" to "Friday"),
                    "en",
                )
                if (result.hasErrors) {
                    throw Mf2Error("format-error", result.errors.toString())
                }
                result.parts
            }
            ?: emptyList()
        println(KotlinFormattedPartJson.toMaps(parts))
    }

    private fun formatOrThrow(
        model: Mf2Model,
        arguments: Map<String, Any?>,
        locale: String,
    ): String {
        val result = Mf2Formatter.formatMessage(model, arguments, locale)
        if (result.hasErrors) {
            throw Mf2Error("format-error", result.errors.toString())
        }
        return result.value
    }
}
