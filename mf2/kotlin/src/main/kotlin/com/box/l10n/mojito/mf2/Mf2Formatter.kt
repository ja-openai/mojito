package com.box.l10n.mojito.mf2

import java.text.Normalizer
import kotlin.math.truncate

private val DECIMAL_LITERAL_PATTERN = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
private val SOURCE_DECIMAL_PATTERN = Regex("(-?)(0|[1-9][0-9]*)(?:\\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?")
private const val MAX_SOURCE_DECIMAL_EXPONENT = 1_000_000
private const val MAX_SOURCE_DECIMAL_KEY_LENGTH = 4096

class Mf2FunctionRegistry internal constructor(
    private val formatters: Map<String, Mf2FunctionFormatter>,
    private val selectors: Map<String, Mf2Selector>,
) {
    companion object {
        @JvmStatic
        fun portable(): Mf2FunctionRegistry = Mf2FunctionRegistries.portable()

        @JvmStatic
        fun defaults(): Mf2FunctionRegistry = Mf2FunctionRegistries.jdk()
    }

    fun withFunction(name: String, formatter: Mf2FunctionFormatter): Mf2FunctionRegistry =
        Mf2FunctionRegistry(formatters + (name to formatter), selectors)

    fun withSelector(name: String, selector: Mf2Selector): Mf2FunctionRegistry =
        Mf2FunctionRegistry(formatters, selectors + (name to selector))

    internal fun hasFormatter(functionRef: Map<String, Any?>): Boolean =
        formatters.containsKey(stringValue(functionRef["name"]))

    internal fun hasSelector(functionRef: Map<String, Any?>): Boolean =
        selectors.containsKey(stringValue(functionRef["name"]))

    internal fun format(call: Mf2FunctionCall): String =
        formatters[stringValue(call.function["name"])]?.invoke(call)
            ?: throw Mf2Error(
                "unsupported-function",
                "Function :${call.function["name"]} is not supported by this formatter registry.",
            )

    internal fun select(match: Mf2FunctionMatch): Int? =
        selectors[stringValue(match.function["name"])]?.invoke(match)
}

object Mf2Formatter {
    @JvmStatic
    private fun formatResult(
        model: Mf2Model,
        arguments: Map<String, Any?> = emptyMap(),
        locale: String = "en",
        bidiIsolation: Mf2BidiIsolation = Mf2BidiIsolation.NONE,
        functions: Mf2FunctionRegistry = Mf2FunctionRegistry.defaults(),
        onMissingArgument: Mf2RecoveryHandler = ::defaultRecovery,
        onFormatError: Mf2RecoveryHandler = ::defaultRecovery,
    ): Mf2FormatResult {
        val result = formatToPartsResult(
            model,
            arguments,
            locale,
            functions,
            onMissingArgument,
            onFormatError,
        )
        return Mf2FormatResult(partsToString(result.parts, bidiIsolation), result.errors)
    }

    @JvmStatic
    fun formatMessage(
        model: Mf2Model,
        arguments: Map<String, Any?> = emptyMap(),
        locale: String = "en",
        bidiIsolation: Mf2BidiIsolation = Mf2BidiIsolation.NONE,
        functions: Mf2FunctionRegistry = Mf2FunctionRegistry.defaults(),
        onMissingArgument: Mf2RecoveryHandler = ::defaultRecovery,
        onFormatError: Mf2RecoveryHandler = ::defaultRecovery,
    ): Mf2FormatResult = formatResult(
        model,
        arguments,
        locale,
        bidiIsolation,
        functions,
        onMissingArgument,
        onFormatError,
    )

    @JvmStatic
    private fun formatToPartsResult(
        model: Mf2Model,
        arguments: Map<String, Any?> = emptyMap(),
        locale: String = "en",
        functions: Mf2FunctionRegistry = Mf2FunctionRegistry.defaults(),
        onMissingArgument: Mf2RecoveryHandler = ::defaultRecovery,
        onFormatError: Mf2RecoveryHandler = ::defaultRecovery,
    ): Mf2PartsResult {
        validateModel(model)
        val context = FormatContext(
            arguments,
            locale,
            functions,
            fallback = true,
            onMissingArgument = onMissingArgument,
            onFormatError = onFormatError,
        )
        context.applyDeclarations(modelDeclarations(model))
        val parts = when (model["type"]) {
            "message" -> context.formatPatternToParts(modelListField(model, "pattern"))
            "select" -> context.formatSelectToParts(modelSelectors(model), modelVariants(model))
            else -> throw Mf2Error("unsupported-message-type", "Unsupported message type: ${model["type"]}")
        }
        return Mf2PartsResult(parts, context.errors)
    }

    @JvmStatic
    fun formatMessageToParts(
        model: Mf2Model,
        arguments: Map<String, Any?> = emptyMap(),
        locale: String = "en",
        functions: Mf2FunctionRegistry = Mf2FunctionRegistry.defaults(),
        onMissingArgument: Mf2RecoveryHandler = ::defaultRecovery,
        onFormatError: Mf2RecoveryHandler = ::defaultRecovery,
    ): Mf2PartsResult = formatToPartsResult(
        model,
        arguments,
        locale,
        functions,
        onMissingArgument,
        onFormatError,
    )

    @JvmStatic
    fun partsToString(
        parts: List<Mf2Part>,
        bidiIsolation: Mf2BidiIsolation = Mf2BidiIsolation.NONE,
    ): String {
        val output = StringBuilder()
        for (part in parts) {
            when (part["type"]) {
                "text" -> output.append(stringValue(part["value"]))
                "fallback" -> output.append(part["value"] as? String ?: fallbackValue(stringValue(part["source"])))
                "expression" -> output.append(
                    isolateExpression(
                        stringValue(part["value"]),
                        bidiIsolation,
                        part["direction"] as? String ?: part["dir"] as? String,
                    ),
                )
            }
        }
        return output.toString()
    }
}

private class FormatContext(
    arguments: Map<String, Any?>,
    locale: String,
    private val functions: Mf2FunctionRegistry,
    private val fallback: Boolean,
    private val onMissingArgument: Mf2RecoveryHandler,
    private val onFormatError: Mf2RecoveryHandler,
) {
    private val arguments = LinkedHashMap(arguments)
    private val locals = linkedMapOf<String, ResolvedValue>()
    private val failedLocals = mutableSetOf<String>()
    private val selectorAnnotations = linkedMapOf<String, SelectorAnnotation>()
    val errors = mutableListOf<Mf2Error>()
    private val locale = locale.takeIf { it.isNotBlank() } ?: "en"

    fun applyDeclarations(declarations: List<Map<String, Any?>>) {
        selectorAnnotations += selectorAnnotations(declarations)
        for (declaration in declarations) {
            when (declaration["type"]) {
                "input" -> applyInputDeclaration(declaration)
                "local" -> {
                    val name = stringValue(declaration["name"])
                    val output = formatExpressionOutput(asMap(declaration["value"]))
                    if (output.hadError) {
                        failedLocals += name
                        locals.remove(name)
                    } else {
                        locals[name] = ResolvedValue(output.rawValue, output.source)
                    }
                }
            }
        }
    }

    private fun applyInputDeclaration(input: Map<String, Any?>) {
        val name = stringValue(input["name"])
        val functionRef = asMap(input["value"])["function"] as? Map<String, Any?> ?: return
        if (!functions.hasFormatter(functionRef) || !functions.hasSelector(functionRef)) return
        if (!hasValue(name)) {
            if (!fallback) throw Mf2Error.missingArgument(name)
            failedLocals += name
            errors += unresolvedVariable(name)
            errors += Mf2Error.badOperand("Function operand is not available.")
            return
        }
        val inputValue = value(name)
        recordFunctionResolutionErrors(functionRef, inputValue.source)
        try {
            val rendered = operandValueToString(inputValue.rawValue)
            val formatted = functions.format(
                Mf2FunctionCall(
                    value = rendered,
                    rawValue = inputValue.rawValue,
                    function = functionRef,
                    locale = locale,
                    optionResolver = { optionName, fallbackValue -> optionValue(functionRef, optionName, fallbackValue) },
                    inheritedSource = inputValue.source,
                ),
            )
            val sourceValue = inputValue.source?.value ?: rendered
            locals[name] =
                ResolvedValue(
                    formatted,
                    Mf2FunctionSource(
                        sourceValue,
                        functionRef,
                        inputValue.source,
                    ) { optionName, fallbackValue -> optionValue(functionRef, optionName, fallbackValue) },
                )
        } catch (error: Mf2Error) {
            if (!fallback) throw error
            errors += fallbackError(error)
            failedLocals += name
            locals.remove(name)
        }
    }

    fun formatSelectToParts(
        selectors: List<Map<String, Any?>>,
        variants: List<Map<String, Any?>>,
    ): List<Mf2Part> {
        val selectorValues = selectors.map { selectorValue(it) }
        val signatures = mutableSetOf<List<String>>()
        var fallbackVariant: Map<String, Any?>? = null
        var selected: Map<String, Any?>? = null
        var selectedRank: List<Int>? = null
        for (variant in variants) {
            validateVariant(variant, selectorValues, signatures)
            val keys = variantKeys(variant)
            if (fallbackVariant == null && keys.all { it["type"] == "*" }) fallbackVariant = variant
            val rank = variantMatchRank(variant, selectorValues)
            val previousRank = selectedRank
            if (rank != null && (previousRank == null || compareRank(rank, previousRank) > 0)) {
                selected = variant
                selectedRank = rank
            }
        }
        if (fallbackVariant == null) throw Mf2Error(
            "missing-fallback-variant",
            "Select messages must include a catch-all fallback variant.",
        )
        val variant = selected ?: fallbackVariant
        return formatPatternToParts(asList(variant["value"]))
    }

    private fun selectorValue(selector: Map<String, Any?>): SelectorValue {
        val name = stringValue(selector["name"])
        val annotation = selectorAnnotations[name]
        if (!hasValue(name)) {
            if (!fallback) throw Mf2Error.missingArgument(name)
            if (!failedLocals.contains(name)) errors += unresolvedVariable(name)
            if (annotation != null && (failedLocals.contains(name) || functions.hasSelector(annotation.function))) {
                if (!failedLocals.contains(name)) errors += Mf2Error.badOperand("Selector operand is not available.")
                errors += Mf2Error.badSelector("Selector operand is not available.")
            }
            return SelectorValue(
                rendered = "",
                rawValue = "",
                normalizedRendered = if (annotation?.isString == true) normalizeStringKey("") else null,
                exactMatch = false,
                selectionKey = null,
                function = annotation?.function,
                source = null,
            )
        }
        val resolved = value(name)
        return try {
            val rendered = operandValueToString(resolved.rawValue)
            recordSelectorResolutionErrors(annotation)
            SelectorValue(
                rendered = rendered,
                rawValue = resolved.rawValue,
                normalizedRendered = if (annotation?.isString == true) normalizeStringKey(rendered) else null,
                exactMatch = annotation == null || annotation.exactMatch,
                selectionKey = selectionKey(locale, annotation, resolved),
                function = annotation?.function,
                source = resolved.source,
            )
        } catch (error: Mf2Error) {
            if (!fallback) throw error
            errors += fallbackError(error)
            if (annotation != null) {
                errors += Mf2Error.badSelector("Selector operand is not available.")
            }
            SelectorValue(
                rendered = "",
                rawValue = "",
                normalizedRendered = if (annotation?.isString == true) normalizeStringKey("") else null,
                exactMatch = false,
                selectionKey = null,
                function = annotation?.function,
                source = resolved.source,
            )
        }
    }

    fun formatPatternToParts(pattern: List<Any?>): List<Mf2Part> {
        val parts = mutableListOf<Mf2Part>()
        for (part in pattern) {
            if (part is String) {
                parts += linkedMapOf("type" to "text", "value" to part)
                continue
            }
            val item = asMap(part)
            when (item["type"]) {
                "expression" -> {
                    val output = formatExpressionOutput(item)
                    if (output.hadError) {
                        val source = output.fallbackSource ?: fallbackSource(item)
                        val fallbackPart = linkedMapOf<String, Any?>("type" to "fallback", "source" to source)
                        if (output.value != fallbackValue(source)) fallbackPart["value"] = output.value
                        parts += fallbackPart
                    } else {
                        val expressionPart = linkedMapOf<String, Any?>("type" to "expression", "value" to output.value)
                        val attributes = asMap(item["attributes"])
                        if (attributes.isNotEmpty()) expressionPart["attributes"] = attributes
                        if (output.direction != null) expressionPart["direction"] = output.direction
                        parts += expressionPart
                    }
                }
                "markup" -> {
                    val options = asMap(item["options"])
                    if (options.containsKey("u:dir")) {
                        val error = Mf2Error.badOption("u:dir is not valid on markup.")
                        if (!fallback) throw error
                        errors += error
                    }
                    val markup = linkedMapOf<String, Any?>(
                        "type" to "markup",
                        "kind" to item["kind"],
                        "name" to item["name"],
                    )
                    if (options.isNotEmpty()) markup["options"] = options
                    val attributes = asMap(item["attributes"])
                    if (attributes.isNotEmpty()) markup["attributes"] = attributes
                    parts += markup
                }
                else -> throw Mf2Error("unsupported-pattern-part", "Unsupported pattern part: ${item["type"]}")
            }
        }
        return parts
    }

    private fun formatExpressionOutput(expression: Map<String, Any?>): ExpressionOutput {
        val arg = expression["arg"] as? Map<String, Any?>
        var value: String
        var rawValue: Any?
        var source: FunctionSource? = null
        when (arg?.get("type")) {
            null -> {
                value = ""
                rawValue = ""
            }
            "literal" -> {
                value = stringValue(arg["value"])
                rawValue = value
            }
            "variable" -> {
                val name = stringValue(arg["name"])
                if (!hasValue(name)) {
                    if (!fallback) throw Mf2Error.missingArgument(name)
                    val error = unresolvedVariable(name)
                    if (!failedLocals.contains(name)) errors += error
                    if (expression["function"] != null) errors += Mf2Error.badOperand("Function operand is not available.")
                    val source = fallbackSource(expression)
                    return ExpressionOutput(
                        recoverMissingArgument(expression, name, source, error),
                        true,
                        null,
                        null,
                        source,
                    )
                }
                val resolved = value(name)
                rawValue = resolved.rawValue
                source = resolved.source
                try {
                    value = operandValueToString(rawValue)
                } catch (error: Mf2Error) {
                    if (!fallback) throw error
                    val recoverable = fallbackError(error)
                    errors += recoverable
                    val source = fallbackSource(expression)
                    return ExpressionOutput(
                        recoverFormatError(expression, source, recoverable),
                        true,
                        null,
                        null,
                        source,
                    )
                }
            }
            else -> throw Mf2Error("unsupported-expression-arg", "Unsupported expression arg: ${arg?.get("type")}")
        }
        val functionRef = expression["function"] as? Map<String, Any?>
        if (functionRef == null) {
            return try {
                ExpressionOutput(
                    primitiveValueToString(rawValue, value),
                    false,
                    source,
                    bidiDirectionFromSource(source),
                    rawValue = rawValue,
                )
            } catch (error: Mf2Error) {
                if (!fallback) throw error
                val recoverable = fallbackError(error)
                errors += recoverable
                val source = fallbackSource(expression)
                ExpressionOutput(
                    recoverFormatError(expression, source, recoverable),
                    true,
                    null,
                    null,
                    source,
                )
            }
        }
        recordFunctionResolutionErrors(functionRef, source)
        return try {
            val direction = bidiDirectionForFunction(functionRef, source)
            val formatted = functions.format(
                Mf2FunctionCall(
                    value = value,
                    rawValue = rawValue,
                    function = functionRef,
                    locale = locale,
                    optionResolver = { optionName, fallbackValue -> optionValue(functionRef, optionName, fallbackValue) },
                    inheritedSource = source,
                ),
            )
            ExpressionOutput(
                formatted,
                false,
                Mf2FunctionSource(
                    source?.value ?: value,
                    functionRef,
                    source,
                ) { optionName, fallbackValue -> optionValue(functionRef, optionName, fallbackValue) },
                direction,
            )
        } catch (error: Mf2Error) {
            if (!fallback) throw error
            val recoverable = fallbackError(error)
            errors += recoverable
            val source = fallbackSource(expression)
            ExpressionOutput(
                recoverFormatError(expression, source, recoverable),
                true,
                null,
                null,
                source,
            )
        }
    }

    private fun primitiveValueToString(rawValue: Any?, fallbackValue: String): String =
        if (rawValue is Number) {
            Mf2NumberCore.format(rawValue, Mf2NumberCore.Options(locale = locale))
        } else {
            fallbackValue
        }

    private fun recoverMissingArgument(
        expression: Map<String, Any?>,
        variableName: String,
        source: String,
        error: Mf2Error,
    ): String =
        recoverValue(
            onMissingArgument,
            Mf2RecoveryContext(
                code = error.code,
                message = error.message ?: "",
                locale = locale,
                variableName = variableName,
                functionName = stringValue(asMap(expression["function"])["name"]).ifBlank { null },
                sourceExpression = expressionSource(expression),
                fallbackValue = fallbackValue(source),
                error = error,
            ),
        )

    private fun recoverFormatError(
        expression: Map<String, Any?>,
        source: String,
        error: Mf2Error,
    ): String {
        val arg = asMap(expression["arg"])
        val variableName = if (arg["type"] == "variable") stringValue(arg["name"]) else null
        return recoverValue(
            onFormatError,
            Mf2RecoveryContext(
                code = error.code,
                message = error.message ?: "",
                locale = locale,
                variableName = variableName,
                functionName = stringValue(asMap(expression["function"])["name"]).ifBlank { null },
                sourceExpression = expressionSource(expression),
                fallbackValue = fallbackValue(source),
                error = error,
            ),
        )
    }

    private fun optionValue(functionRef: Map<String, Any?>, optionName: String, fallbackValue: String?): String? {
        val option = asMap(functionRef["options"])[optionName] ?: return fallbackValue
        val optionMap = asMap(option)
        return when (optionMap["type"]) {
            "literal" -> stringValue(optionMap["value"])
            "variable" -> {
                val name = stringValue(optionMap["name"])
                if (!hasValue(name)) throw Mf2Error.missingArgument(name)
                optionValueToString(value(name).rawValue)
            }
            else -> fallbackValue
        }
    }

    private fun hasValue(name: String): Boolean = !failedLocals.contains(name) && (locals.containsKey(name) || arguments.containsKey(name))

    private fun value(name: String): ResolvedValue = locals[name] ?: ResolvedValue(arguments[name], null)

    private fun recordFunctionResolutionErrors(functionRef: Map<String, Any?>, source: FunctionSource?) {
        if (!isNumericFunction(functionRef)) return
        if (!numericSelectUsesVariable(functionRef) && !inheritedExactNumericSource(source)) return
        val error = Mf2Error.badOption("Numeric select option is not valid in this context.")
        if (!fallback) throw error
        errors += error
    }

    private fun recordSelectorResolutionErrors(annotation: SelectorAnnotation?) {
        if (annotation?.function?.get("name") != "currency") return
        val error = Mf2Error.badSelector("Currency selector is not supported.")
        if (!fallback) throw error
        errors += error
    }

    private fun validateVariant(
        variant: Map<String, Any?>,
        selectorValues: List<SelectorValue>,
        signatures: MutableSet<List<String>>,
    ) {
        val keys = variantKeys(variant)
        if (keys.size != selectorValues.size) throw Mf2Error(
            "variant-key-count-mismatch",
            "Variant key count must match selector count.",
        )
        val signature = variantKeySignature(keys, selectorValues)
        if (!signatures.add(signature)) throw Mf2Error(
            "duplicate-variant",
            "Select variants must have unique key tuples.",
        )
    }

    private fun variantMatchRank(variant: Map<String, Any?>, selectorValues: List<SelectorValue>): List<Int>? {
        val keys = variantKeys(variant)
        if (keys.size != selectorValues.size) return null
        return keys.mapIndexed { index, key -> keyMatchRank(key, selectorValues[index]) ?: return null }
    }

    private fun keyMatchRank(key: Map<String, Any?>, selector: SelectorValue): Int? {
        if (key["type"] == "*") return 0
        val keyValue = stringValue(key["value"])
        if (selector.exactMatch && numericLiteralKeyMatchesSource(keyValue, selector)) return 3
        if (selector.exactMatch && (selector.function == null || !isNumericFunction(selector.function)) && literalKeyMatches(keyValue, selector)) return 2
        if (keyValue == selector.selectionKey) return 1
        val functionRef = selector.function ?: return null
        return try {
            functions.select(
                Mf2FunctionMatch(
                    value = selector.rendered,
                    rawValue = selector.rawValue,
                    function = functionRef,
                    key = keyValue,
                    locale = locale,
                    optionResolver = { optionName, fallbackValue -> optionValue(functionRef, optionName, fallbackValue) },
                    inheritedSource = selector.source,
                ),
            )
        } catch (error: Mf2Error) {
            if (!fallback) throw error
            errors += fallbackError(error)
            errors += Mf2Error.badSelector("Selector failed to match.")
            null
        }
    }
}

private fun validateModel(model: Mf2Model) {
    val declarations = modelDeclarations(model)
    validateDeclarations(declarations)
    when (model["type"]) {
        "message" -> validatePattern(modelListField(model, "pattern"))
        "select" -> {
            validateSelectorAnnotations(declarations, modelSelectors(model))
            for (variant in modelVariants(model)) validatePattern(modelListField(variant, "value"))
        }
    }
}

private fun validateDeclarations(declarations: List<Map<String, Any?>>) {
    val names = mutableSetOf<String>()
    for (declaration in declarations) {
        if (declaration["value"] != null) validateExpression(modelObject(declaration["value"], "Expression"))
        val name = stringValue(declaration["name"])
        if (declaration["type"] == "input") validateInputDeclaration(declaration)
        if (!names.add(name)) throw Mf2Error("duplicate-declaration", "Declaration $$name is defined more than once.")
    }
    validateLocalReferences(declarations)
}

private fun validateLocalReferences(declarations: List<Map<String, Any?>>) {
    val forbidden = mutableSetOf<String>()
    for (declaration in declarations.asReversed()) {
        if (declaration["type"] != "local") continue
        val name = stringValue(declaration["name"])
        forbidden += name
        if (expressionReferencesAny(asMap(declaration["value"]), forbidden)) {
            throw Mf2Error("duplicate-declaration", "Declaration $$name is defined more than once.")
        }
    }
}

private fun expressionReferencesAny(expression: Map<String, Any?>, names: Set<String>): Boolean =
    argReferencesAny(asMap(expression["arg"]), names) ||
        asMap(expression["function"])
            .let { asMap(it["options"]).values }
            .any { argReferencesAny(asMap(it), names) }

private fun argReferencesAny(arg: Map<String, Any?>, names: Set<String>): Boolean =
    arg["type"] == "variable" && names.contains(stringValue(arg["name"]))

private fun validateInputDeclaration(declaration: Map<String, Any?>) {
    val name = stringValue(declaration["name"])
    val arg = asMap(asMap(declaration["value"])["arg"])
    if (arg["type"] == "variable" && arg["name"] == name) return
    throw Mf2Error("invalid-input-declaration", "Input declaration $$name must bind the same variable name.")
}

private fun validatePattern(pattern: List<Any?>) {
    for (part in pattern) {
        if (part is String) {
            if (part.isEmpty()) throw Mf2Error("invalid-pattern-text", "Pattern text parts must be non-empty.")
            continue
        }
        @Suppress("UNCHECKED_CAST")
        val item = part as? Map<String, Any?> ?: throw Mf2Error("unsupported-pattern-part", "Unsupported pattern part: ")
        when (item["type"]) {
            "expression" -> validateExpression(item)
            "markup" -> validateMarkup(item)
            else -> throw Mf2Error("unsupported-pattern-part", "Unsupported pattern part: ${item["type"]}")
        }
    }
}

private fun modelObject(value: Any?, label: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return value as? Map<String, Any?> ?: throw Mf2Error("bad-option", "$label must be an object.")
}

private fun validateExpression(expression: Map<String, Any?>) {
    if (expression["arg"] != null && expression["arg"] !is Map<*, *>) throw Mf2Error(
        "unsupported-expression-arg",
        "Unsupported expression arg: ",
    )
    if (expression["function"] != null) validateFunctionRef(modelObject(expression["function"], "Function reference"))
    validateAttributesMap(expression["attributes"], "expression attributes")
}

private fun validateFunctionRef(functionRef: Map<String, Any?>) {
    validateOptionsMap(functionRef["options"], "function options")
}

private fun validateOptionsMap(options: Any?, label: String) {
    if (options == null) return
    @Suppress("UNCHECKED_CAST")
    val optionMap = options as? Map<String, Any?> ?: throw Mf2Error("bad-option", "$label must be an object.")
    for (option in optionMap.values) {
        if (option !is Map<*, *>) throw Mf2Error("bad-option", "$label values must be objects.")
    }
}

private fun validateAttributesMap(attributes: Any?, label: String) {
    if (attributes == null) return
    @Suppress("UNCHECKED_CAST")
    val attributeMap = attributes as? Map<String, Any?> ?: throw Mf2Error("bad-option", "$label must be an object.")
    for (attribute in attributeMap.values) {
        if (attribute == true || attribute is Map<*, *>) continue
        throw Mf2Error("bad-option", "$label values must be true or objects.")
    }
}

private fun validateMarkup(markup: Map<String, Any?>) {
    validateOptionsMap(markup["options"], "markup options")
    validateAttributesMap(markup["attributes"], "markup attributes")
    if (stringValue(markup["kind"]) in setOf("open", "standalone", "close")) return
    throw Mf2Error("invalid-markup-kind", "Markup kind must be open, standalone, or close.")
}

private fun validateSelectorAnnotations(declarations: List<Map<String, Any?>>, selectors: List<Map<String, Any?>>) {
    val annotations = selectorAnnotations(declarations)
    for (selector in selectors) {
        val name = stringValue(selector["name"])
        if (!annotations.containsKey(name)) throw Mf2Error(
            "missing-selector-annotation",
            "Selector $$name must reference a declaration with a function.",
        )
    }
}

private fun selectorAnnotations(declarations: List<Map<String, Any?>>): Map<String, SelectorAnnotation> {
    val expressions = linkedMapOf<String, Map<String, Any?>>()
    val annotations = linkedMapOf<String, SelectorAnnotation>()
    for (declaration in declarations) {
        val name = stringValue(declaration["name"])
        val expression = asMap(declaration["value"])
        expressions[name] = expression
        val functionRef = expression["function"] as? Map<String, Any?>
        if (functionRef != null) annotations[name] = SelectorAnnotation.from(functionRef)
    }
    var changed = true
    while (changed) {
        changed = false
        for ((name, expression) in expressions) {
            if (annotations.containsKey(name)) continue
            val arg = asMap(expression["arg"])
            if (arg["type"] != "variable") continue
            val inherited = annotations[stringValue(arg["name"])] ?: continue
            annotations[name] = inherited
            changed = true
        }
    }
    return annotations
}

private fun modelDeclarations(model: Mf2Model): List<Map<String, Any?>> =
    modelObjectEntries(modelListField(model, "declarations"), "declarations")

private fun modelSelectors(model: Mf2Model): List<Map<String, Any?>> =
    modelObjectEntries(modelListField(model, "selectors"), "selectors")

private fun modelVariants(model: Mf2Model): List<Map<String, Any?>> =
    modelObjectEntries(modelListField(model, "variants"), "variants")

private fun modelListField(model: Map<String, Any?>, name: String): List<Any?> {
    val value = model[name] ?: return emptyList()
    if (value is List<*>) return value
    throw Mf2Error("bad-option", "$name must be an array.")
}

private fun modelObjectEntries(values: List<Any?>, name: String): List<Map<String, Any?>> =
    values.map { value ->
        @Suppress("UNCHECKED_CAST")
        value as? Map<String, Any?> ?: throw Mf2Error("bad-option", "$name entries must be objects.")
    }

private fun variantKeys(variant: Map<String, Any?>): List<Map<String, Any?>> =
    modelObjectEntries(modelListField(variant, "keys"), "variant keys")

private fun variantKeySignature(keys: List<Map<String, Any?>>, selectorValues: List<SelectorValue>): List<String> =
    keys.mapIndexed { index, key ->
        if (key["type"] == "*") {
            "*"
        } else {
            val selector = selectorValues[index]
            "=" + if (selector.normalizedRendered == null) {
                stringValue(key["value"])
            } else {
                normalizeStringKey(stringValue(key["value"]))
            }
        }
    }

private fun compareRank(left: List<Int>, right: List<Int>): Int {
    val size = minOf(left.size, right.size)
    for (index in 0 until size) {
        val comparison = left[index].compareTo(right[index])
        if (comparison != 0) return comparison
    }
    return left.size.compareTo(right.size)
}

private fun literalKeyMatches(value: String, selector: SelectorValue): Boolean =
    if (selector.normalizedRendered == null) {
        value == selector.rendered
    } else {
        normalizeStringKey(value) == selector.normalizedRendered
    }

private fun numericLiteralKeyMatchesSource(value: String, selector: SelectorValue): Boolean {
    val sourceKey = preferredNumericSourceKey(selector) ?: return false
    return value == sourceKey && DECIMAL_LITERAL_PATTERN.matches(value)
}

private fun preferredNumericSourceKey(selector: SelectorValue): String? {
    val functionName = stringValue(selector.function?.get("name"))
    if (functionName != "number" && functionName != "percent") return null
    val sourceValue = numericSourceValue(selector.source, functionName) ?: return null
    val operand = parseSourceDecimal(sourceValue) ?: return null
    if (functionName == "percent") {
        return renderSourceDecimal(operand.copy(scale = operand.scale - 2), trimFractionZeros = false)
    }
    return if (operand.hasExponent) renderSourceDecimal(operand, trimFractionZeros = true) else sourceValue
}

private fun numericSourceValue(source: FunctionSource?, functionName: String): String? {
    var current = source
    while (current != null) {
        if (current.function["name"] == functionName) return current.value
        current = current.inherited
    }
    return null
}

private data class SourceDecimal(
    val negative: Boolean,
    val digits: String,
    val scale: Int,
    val hasExponent: Boolean,
)

private fun parseSourceDecimal(value: String): SourceDecimal? {
    val match = SOURCE_DECIMAL_PATTERN.matchEntire(value) ?: return null
    val exponent = parseSourceExponent(match.groupValues.getOrElse(4) { "" }) ?: return null
    val fraction = match.groupValues.getOrElse(3) { "" }
    var digits = "${match.groupValues[2]}$fraction".replace(Regex("^0+"), "")
    if (digits.isEmpty()) digits = "0"
    return SourceDecimal(
        negative = match.groupValues[1] == "-" && digits != "0",
        digits = digits,
        scale = fraction.length - exponent,
        hasExponent = match.groupValues.getOrElse(4) { "" }.isNotEmpty(),
    )
}

private fun parseSourceExponent(value: String): Int? {
    if (value.isEmpty()) return 0
    val negative = value.startsWith("-")
    val unsigned = if (negative || value.startsWith("+")) value.drop(1) else value
    val digits = unsigned.replace(Regex("^0+"), "").ifEmpty { "0" }
    if (digits.length > 7) return null
    val parsed = digits.toIntOrNull() ?: return null
    if (parsed > MAX_SOURCE_DECIMAL_EXPONENT) return null
    return if (negative) -parsed else parsed
}

private fun renderSourceDecimal(operand: SourceDecimal, trimFractionZeros: Boolean): String? {
    val extraLength =
        if (operand.scale > operand.digits.length) {
            operand.scale - operand.digits.length
        } else {
            maxOf(-operand.scale, 0)
        }
    if (operand.digits.length + extraLength + 2 > MAX_SOURCE_DECIMAL_KEY_LENGTH) return null
    var text =
        when {
            operand.scale <= 0 -> operand.digits + "0".repeat(-operand.scale)
            operand.scale >= operand.digits.length -> "0." + "0".repeat(operand.scale - operand.digits.length) + operand.digits
            else -> {
                val split = operand.digits.length - operand.scale
                operand.digits.substring(0, split) + "." + operand.digits.substring(split)
            }
        }
    if (trimFractionZeros && text.contains(".")) text = text.replace(Regex("\\.?0+$"), "")
    return if (operand.negative) "-$text" else text
}

private fun selectionKey(locale: String, annotation: SelectorAnnotation?, resolvedValue: ResolvedValue): String? {
    if (annotation == null || !annotation.isNumeric || annotation.numberSelect == "exact") return null
    var operand = operandValueToString(resolvedValue.rawValue)
    if (annotation.function["name"] == "percent") {
        operand = if (operand.endsWith("%")) {
            operand.dropLast(1)
        } else {
            val sourceValue = resolvedValue.source?.value ?: operand
            val sourceNumber = sourceValue.toDoubleOrNull() ?: return null
            formatNumberValue(sourceNumber * 100.0)
        }
    }
    return selectPluralCategory(locale, operand, annotation.numberSelect)
}

private fun selectPluralCategory(locale: String, value: String, select: String): String? =
    try {
        if (select == "ordinal") PluralRules.selectOrdinal(locale, value) else PluralRules.selectCardinal(locale, value)
    } catch (_: RuntimeException) {
        null
    }

private fun normalizeStringKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

private fun fallbackError(error: Mf2Error): Mf2Error =
    if (error.code == "unsupported-function") Mf2Error("unknown-function", error.message ?: "") else error

private fun unresolvedVariable(name: String): Mf2Error =
    Mf2Error.unresolvedVariable(name)

private fun fallbackSource(expression: Map<String, Any?>): String {
    val arg = expression["arg"] as? Map<String, Any?>
    if (arg != null) return expressionArgSource(arg)
    val functionRef = expression["function"] as? Map<String, Any?>
    return if (functionRef != null) functionNameSource(functionRef) else ""
}

private fun fallbackValue(source: String): String = "{$source}"

private fun defaultRecovery(context: Mf2RecoveryContext): String = context.fallbackValue

private fun recoverValue(handler: Mf2RecoveryHandler, context: Mf2RecoveryContext): String =
    handler(context) ?: context.fallbackValue

private fun expressionSource(expression: Map<String, Any?>): String {
    val items = mutableListOf<String>()
    val arg = expression["arg"] as? Map<String, Any?>
    if (arg != null) items += expressionArgSource(arg)
    val functionRef = expression["function"] as? Map<String, Any?>
    if (functionRef != null) items += functionSource(functionRef)
    return items.joinToString(prefix = "{", postfix = "}", separator = " ")
}

private fun expressionArgSource(arg: Map<String, Any?>): String =
    if (arg["type"] == "variable") "$${stringValue(arg["name"])}" else quoteLiteralSource(stringValue(arg["value"]))

private fun functionSource(functionRef: Map<String, Any?>): String {
    val source = StringBuilder(":").append(stringValue(functionRef["name"]))
    for ((name, value) in asMap(functionRef["options"])) {
        source.append(" ").append(name).append("=").append(expressionArgSource(asMap(value)))
    }
    return source.toString()
}

private fun functionNameSource(functionRef: Map<String, Any?>): String = ":${stringValue(functionRef["name"])}"

private fun quoteLiteralSource(value: String): String =
    buildString {
        append("|")
        for (char in value) {
            if (char == '\\' || char == '|') append("\\")
            append(char)
        }
        append("|")
    }

private fun isolateExpression(value: String, bidiIsolation: Mf2BidiIsolation, direction: String?): String =
    if (bidiIsolation == Mf2BidiIsolation.DEFAULT) "${bidiMarker(direction)}$value\u2069" else value

private fun bidiMarker(direction: String?): Char =
    when (direction ?: "auto") {
        "ltr" -> '\u2066'
        "rtl" -> '\u2067'
        else -> '\u2068'
    }

private fun bidiDirectionForFunction(functionRef: Map<String, Any?>, source: FunctionSource?): String? {
    val value = functionOptionLiteral(functionRef, "u:dir", null)
    if (value != null) return parseBidiDirection(value)
    return bidiDirectionFromSource(source)
}

private fun bidiDirectionFromSource(source: FunctionSource?): String? {
    if (source == null) return null
    val value = functionOptionLiteral(source.function, "u:dir", null)
    if (value != null) return parseBidiDirection(value)
    return bidiDirectionFromSource(source.inherited)
}

private fun parseBidiDirection(value: String): String =
    if (value in setOf("auto", "ltr", "rtl")) value else throw Mf2Error.badOption("u:dir option must be auto, ltr, or rtl.")

private fun valueToString(value: Any?): String =
    when (value) {
        null -> ""
        is String -> value
        is Boolean -> if (value) "true" else "false"
        is Float -> formatNumberValue(value.toDouble())
        is Double -> formatNumberValue(value)
        is Number -> value.toString()
        else -> value.toString()
    }

private fun operandValueToString(value: Any?): String =
    try {
        valueToString(value)
    } catch (_: Exception) {
        throw Mf2Error.badOperand("Value could not be rendered.")
    }

private fun optionValueToString(value: Any?): String =
    try {
        valueToString(value)
    } catch (_: Exception) {
        throw Mf2Error.badOption("Function option value could not be rendered.")
    }

private fun formatNumberValue(value: Double): String =
    if (value.isFinite() && value == truncate(value)) value.toLong().toString() else value.toString()

private data class ResolvedValue(
    val rawValue: Any?,
    val source: FunctionSource?,
)

private typealias FunctionSource = Mf2FunctionSource

private data class ExpressionOutput(
    val value: String,
    val hadError: Boolean,
    val source: FunctionSource?,
    val direction: String?,
    val fallbackSource: String? = null,
    val rawValue: Any? = value,
)

private data class SelectorValue(
    val rendered: String,
    val rawValue: Any?,
    val normalizedRendered: String?,
    val exactMatch: Boolean,
    val selectionKey: String?,
    val function: Map<String, Any?>?,
    val source: FunctionSource?,
)

private data class SelectorAnnotation(
    val function: Map<String, Any?>,
    val numberSelect: String,
) {
    val exactMatch: Boolean
        get() = function["name"] == "string" || (isNumeric && numberSelect == "exact")

    val isString: Boolean
        get() = function["name"] == "string"

    val isNumeric: Boolean
        get() = isNumericFunction(function)

    companion object {
        fun from(functionRef: Map<String, Any?>): SelectorAnnotation {
            val select = functionOptionLiteral(functionRef, "select", null)
            return SelectorAnnotation(functionRef, if (select == "ordinal" || select == "exact") select else "plural")
        }
    }
}
