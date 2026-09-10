package com.box.l10n.mojito.mf2

import java.text.Normalizer
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.truncate

class Mf2FunctionRegistry internal constructor(
    private val formatters: Map<String, Mf2FunctionFormatter>,
    private val selectors: Map<String, Mf2Selector>,
    private val numericFormatters: Set<String> = emptySet(),
) {
    companion object {
        @JvmStatic
        fun portable(): Mf2FunctionRegistry = Mf2FunctionRegistries.portable()

        @JvmStatic
        fun defaults(): Mf2FunctionRegistry = Mf2FunctionRegistries.jdk()
    }

    fun withFunction(name: String, formatter: Mf2FunctionFormatter): Mf2FunctionRegistry =
        Mf2FunctionRegistry(formatters + (name to formatter), selectors, numericFormatters - name)

    fun withSelector(name: String, selector: Mf2Selector): Mf2FunctionRegistry =
        Mf2FunctionRegistry(formatters, selectors + (name to selector), numericFormatters)

    /** Registers numeric output with the locale's direction; explicit u:dir still applies.
     * Ordinary withFunction overrides clear this guarantee. */
    fun withNumericFunction(name: String, formatter: Mf2FunctionFormatter): Mf2FunctionRegistry =
        Mf2FunctionRegistry(formatters + (name to formatter), selectors, numericFormatters + name)

    internal fun isNumericFormatter(function: Map<String, Any?>): Boolean =
        function["name"] in numericFormatters

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
        return Mf2FormatResult(renderPartsToString(result.parts, bidiIsolation, result.expressionIsolation), result.errors)
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
    ): RenderResult {
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
            "message" -> context.formatPatternToParts(asList(model["pattern"]))
            "select" -> context.formatSelectToParts(modelSelectors(model), modelVariants(model))
            else -> throw Mf2Error("unsupported-message-type", "Unsupported message type: ${model["type"]}")
        }
        return RenderResult(parts, context.errors, context.expressionIsolation)
    }

    private data class RenderResult(val parts: List<Mf2Part>, val errors: List<Mf2Error>, val expressionIsolation: List<Boolean>)

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
    ).let { Mf2PartsResult(it.parts, it.errors) }

    @JvmStatic
    fun partsToString(
        parts: List<Mf2Part>,
        bidiIsolation: Mf2BidiIsolation = Mf2BidiIsolation.NONE,
    ): String = renderPartsToString(parts, bidiIsolation, null)

    private fun renderPartsToString(parts: List<Mf2Part>, bidiIsolation: Mf2BidiIsolation, expressionIsolation: List<Boolean>?): String {
        val output = StringBuilder()
        var expressionIndex = 0
        for (part in parts) {
            when (part["type"]) {
                "text" -> output.append(stringValue(part["value"]))
                "fallback" -> output.append(part["value"] as? String ?: fallbackValue(stringValue(part["source"])))
                "expression" -> output.append(
                    isolateExpression(
                        stringValue(part["value"]),
                        if (expressionIsolation?.get(expressionIndex++) == false) Mf2BidiIsolation.NONE else bidiIsolation,
                        part["dir"] as? String,
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
    private val arguments = arguments.mapKeys { normalizeStringKey(it.key) }
    private val locals = linkedMapOf<String, ResolvedValue>()
    private val failedLocals = mutableSetOf<String>()
    private val failedSelectors = Collections.newSetFromMap(IdentityHashMap<SelectorValue, Boolean>())
    private val selectorAnnotations = linkedMapOf<String, SelectorAnnotation>()
    val errors = mutableListOf<Mf2Error>()
    val expressionIsolation = mutableListOf<Boolean>()
    private val locale = locale.takeIf { it.isNotBlank() } ?: "en"
    private val localeIsLtr = localeIsLtr(this.locale)

    fun applyDeclarations(declarations: List<Map<String, Any?>>) {
        selectorAnnotations += selectorAnnotations(declarations)
        for (declaration in declarations) {
            when (declaration["type"]) {
                "input" -> applyInputDeclaration(declaration)
                "local" -> {
                    val name = normalizeStringKey(stringValue(declaration["name"]))
                    val output = formatExpressionOutput(asMap(declaration["value"]))
                    if (output.hadError) {
                        failedLocals += name
                        locals.remove(name)
                    } else {
                        locals[name] = ResolvedValue(output.value, output.source)
                    }
                }
            }
        }
    }

    private fun applyInputDeclaration(input: Map<String, Any?>) {
        val name = normalizeStringKey(stringValue(input["name"]))
        val functionRef = (asMap(input["value"])["function"] as? Map<String, Any?>)?.let(::immutableFunction) ?: return
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
        val bidi = resolveBidi(functionRef, inputValue.source)
        try {
            val rendered = valueToString(inputValue.rawValue)
            val formatted = functions.format(
                Mf2FunctionCall(
                    value = rendered,
                    rawValue = inputValue.rawValue,
                    function = functionRef,
                    locale = locale,
                    optionResolver = { optionName, fallbackValue ->
                        resolvedOptionValue(functionRef, inputValue.source, optionName, fallbackValue)
                    },
                    inheritedSource = inputValue.source,
                ),
            )
            val sourceValue = inputValue.source?.value ?: rendered
            locals[name] = ResolvedValue(
                formatted,
                Mf2FunctionSource(sourceValue, functionRef, inputValue.source, Mf2SourceCache({ optionName, fallbackValue ->
                    optionValue(functionRef, optionName, fallbackValue)
                }, functionRef, inputValue.source, bidi.direction, bidi.force, bidi.resolvedDirection)).also { it.bindCache() },
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
            if (rank != null && (selectedRank == null || compareRank(rank, selectedRank!!) > 0)) {
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
        val name = normalizeStringKey(stringValue(selector["name"]))
        val annotation = selectorAnnotations[name]
        if (!hasValue(name)) {
            if (!fallback) throw Mf2Error.missingArgument(name)
            if (!failedLocals.contains(name)) errors += unresolvedVariable(name)
            if (annotation != null && !annotation.isString) {
                if (!failedLocals.contains(name)) errors += Mf2Error.badOperand("Selector operand is not available.")
                errors += Mf2Error.badSelector("Selector operand is not available.")
            }
            return SelectorValue(
                rendered = "",
                normalizedRendered = if (annotation?.isString == true) normalizeStringKey("") else null,
                exactMatch = false,
                selectionKey = null,
                function = null,
                source = null,
            )
        }
        val resolved = value(name)
        val rendered = valueToString(resolved.rawValue)
        recordSelectorResolutionErrors(annotation)
        return SelectorValue(
            rendered = rendered,
            normalizedRendered = if (annotation?.isString == true) normalizeStringKey(rendered) else null,
            exactMatch = annotation == null || annotation.exactMatch,
            selectionKey = try {
                selectionKey(locale, annotation, resolved) { name, fallback ->
                    if (annotation == null) fallback else resolvedOptionValue(annotation.function, resolved.source, name, fallback)
                }
            } catch (error: Mf2Error) {
                if (!fallback) throw error
                errors += error
                null
            },
            function = annotation?.function,
            source = resolved.source,
        )
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
                        val function = item["function"] as? Map<String, Any?> ?: output.source?.function ?: emptyMap()
                        expressionIsolation += !(localeIsLtr && !output.forceIsolation && output.resolvedDirection == "ltr")
                        val expressionPart = linkedMapOf<String, Any?>("type" to "expression", "value" to output.value)
                        val attributes = asMap(item["attributes"])
                        if (attributes.isNotEmpty()) expressionPart["attributes"] = detachedModelFields(attributes)
                        if (output.direction != null) expressionPart["dir"] = output.direction
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
                    if (options.isNotEmpty()) markup["options"] = detachedModelFields(options)
                    val attributes = asMap(item["attributes"])
                    if (attributes.isNotEmpty()) markup["attributes"] = detachedModelFields(attributes)
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
                val name = normalizeStringKey(stringValue(arg["name"]))
                if (!hasValue(name)) {
                    if (!fallback) throw Mf2Error.missingArgument(name)
                    val error = unresolvedVariable(name)
                    if (!failedLocals.contains(name)) errors += error
                    if (expression["function"] != null) errors += if (functions.hasFormatter(asMap(expression["function"]))) Mf2Error.badOperand("Function operand is not available.") else Mf2Error("unknown-function", "Unknown function.")
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
                value = valueToString(rawValue)
                source = resolved.source
            }
            else -> throw Mf2Error("unsupported-expression-arg", "Unsupported expression arg: ${arg?.get("type")}")
        }
        val functionRef = (expression["function"] as? Map<String, Any?>)?.let(::immutableFunction)
            ?: if (source == null && rawValue is Number) mapOf("type" to "function", "name" to "number") else null ?: return ExpressionOutput(
            value,
            false,
            source,
            bidiDirectionFromSource(source),
            forceIsolation = source?.cache?.forceIsolation ?: false,
        )
        recordFunctionResolutionErrors(functionRef, source)
        val bidi = resolveBidi(functionRef, source)
        return try {
            val formatted = functions.format(
                Mf2FunctionCall(
                    value = value,
                    rawValue = rawValue,
                    function = functionRef,
                    locale = locale,
                    optionResolver = { optionName, fallbackValue ->
                        resolvedOptionValue(functionRef, source, optionName, fallbackValue)
                    },
                    inheritedSource = source,
                ),
            )
            ExpressionOutput(
                formatted,
                false,
                Mf2FunctionSource(source?.value ?: value, functionRef, source, Mf2SourceCache({ optionName, fallbackValue ->
                    optionValue(functionRef, optionName, fallbackValue)
                }, functionRef, source, bidi.direction, bidi.force, bidi.resolvedDirection)).also { it.bindCache() },
                bidi.direction,
                forceIsolation = bidi.force,
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

    private fun resolveBidi(function: Map<String, Any?>, source: FunctionSource?): BidiState {
        val inherited = bidiDirectionFromSource(source)
        val resolved = source?.cache?.resolvedDirection ?: if (inherited == null && localeIsLtr && functions.isNumericFormatter(function)) "ltr" else null
        val option = asMap(function["options"])["u:dir"] ?: return BidiState(inherited, false, resolved)
        val optionMap = asMap(option)
        val raw = if (optionMap["type"] == "variable") {
            val name = normalizeStringKey(stringValue(optionMap["name"]))
            if (!hasValue(name)) {
                errors += unresolvedVariable(name)
                errors += Mf2Error.badOption("u:dir option must resolve to ltr, rtl, auto, or inherit.")
                return BidiState(inherited, false, resolved)
            }
            value(name).rawValue
        } else optionMap["value"]
        if (raw == "inherit") return BidiState(inherited, false, resolved)
        if (raw is String && raw in setOf("ltr", "rtl", "auto")) return BidiState(raw, true, if (raw == "auto") null else raw)
        errors += Mf2Error.badOption("u:dir option must resolve to ltr, rtl, auto, or inherit.")
        return BidiState(inherited, false, resolved)
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
                val name = normalizeStringKey(stringValue(optionMap["name"]))
                if (!hasValue(name)) throw Mf2Error.missingArgument(name)
                val resolved = value(name)
                if (optionName in setOf("add", "subtract", "minimumFractionDigits", "maximumFractionDigits") && isNumericFunction(functionRef)) {
                    numericSourceOperand(resolved.source) ?: valueToString(resolved.rawValue)
                } else valueToString(resolved.rawValue)
            }
            else -> fallbackValue
        }
    }

    private fun resolvedOptionValue(
        functionRef: Map<String, Any?>,
        source: Mf2FunctionSource?,
        optionName: String,
        fallbackValue: String?,
    ): String? {
        if (optionName == "u:dir") return fallbackValue
        if (asMap(functionRef["options"]).containsKey(optionName)) {
            return optionValue(functionRef, optionName, fallbackValue)
        }
        return inheritedNumericOptionValue(
            stringValue(functionRef["name"]),
            source,
            optionName,
            fallbackValue,
        )
    }

    private fun hasValue(name: String): Boolean = !failedLocals.contains(name) && (locals.containsKey(name) || arguments.containsKey(name))

    private fun value(name: String): ResolvedValue = locals[name] ?: ResolvedValue(arguments[name], null)

    private fun recordFunctionResolutionErrors(functionRef: Map<String, Any?>, source: FunctionSource?) {
        if (!isNumericFunction(functionRef)) return
        if (
            !numericSelectUsesVariable(functionRef) &&
            inheritedNumericOptionValue(
                stringValue(functionRef["name"]),
                source,
                "select",
                null,
            ) != "exact"
        ) {
            return
        }
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
        if ((selector.exactMatch && literalKeyMatches(keyValue, selector)) || keyValue == selector.selectionKey) return 1
        if (failedSelectors.contains(selector)) return null
        val functionRef = selector.function ?: return null
        return try {
            functions.select(
                Mf2FunctionMatch(
                    value = selector.rendered,
                    rawValue = selector.rendered,
                    function = functionRef,
                    key = keyValue,
                    locale = locale,
                    optionResolver = { optionName, fallbackValue ->
                        resolvedOptionValue(functionRef, selector.source, optionName, fallbackValue)
                    },
                    inheritedSource = selector.source,
                ),
            )
        } catch (error: Mf2Error) {
            if (!fallback) throw error
            errors += fallbackError(error)
            if (error.code != "bad-variant-key") {
                failedSelectors += selector
                if (error.code != "bad-selector") errors += Mf2Error.badSelector("Selector failed to match.")
            }
            null
        }
    }
}

private fun validateModel(model: Mf2Model) {
    validateModelShape(model)
    val declarations = modelDeclarations(model)
    validateDeclarations(declarations)
    when (model["type"]) {
        "message" -> validatePattern(asList(model["pattern"]))
        "select" -> {
            validateSelectorAnnotations(declarations, modelSelectors(model))
            val annotations = selectorAnnotations(declarations)
            val selectors = modelSelectors(model).map { selector ->
                SelectorValue("", if (annotations[normalizeStringKey(stringValue(selector["name"]))]?.isString == true) "" else null, false, null, null, null)
            }
            val signatures = mutableSetOf<List<String>>()
            var fallback = false
            for (variant in modelVariants(model)) {
                val keys = variantKeys(variant)
                if (keys.size != selectors.size) throw Mf2Error("variant-key-count-mismatch", "Variant key count must match selector count.")
                if (!signatures.add(variantKeySignature(keys, selectors))) throw Mf2Error("duplicate-variant", "Select variants must have unique key tuples.")
                fallback = fallback || keys.all { it["type"] == "*" }
            }
            if (!fallback) throw Mf2Error("missing-fallback-variant", "Select messages must include a catch-all fallback variant.")
            for (variant in modelVariants(model)) validatePattern(asList(variant["value"]))
        }
    }
}

private fun validateDeclarations(declarations: List<Map<String, Any?>>) {
    val names = mutableSetOf<String>()
    for (declaration in declarations) {
        val name = normalizeStringKey(stringValue(declaration["name"]))
        if (declaration["type"] == "input") validateInputDeclaration(declaration)
        if (!names.add(name)) throw Mf2Error("duplicate-declaration", "Declaration $$name is defined more than once.")
    }
    validateLocalReferences(declarations)
}

private fun validateLocalReferences(declarations: List<Map<String, Any?>>) {
    val forbidden = mutableSetOf<String>()
    for (declaration in declarations.asReversed()) {
        if (declaration["type"] != "local") continue
        val name = normalizeStringKey(stringValue(declaration["name"]))
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
    arg["type"] == "variable" && names.contains(normalizeStringKey(stringValue(arg["name"])))

private fun validateInputDeclaration(declaration: Map<String, Any?>) {
    val name = normalizeStringKey(stringValue(declaration["name"]))
    val arg = asMap(asMap(declaration["value"])["arg"])
    if (arg["type"] == "variable" && normalizeStringKey(stringValue(arg["name"])) == name) return
    throw Mf2Error("invalid-input-declaration", "Input declaration $$name must bind the same variable name.")
}

private fun validatePattern(pattern: List<Any?>) {
    for (part in pattern) {
        if (part is String && part.isEmpty()) throw Mf2Error("invalid-pattern-text", "Pattern text parts must be non-empty.")
        val item = asMap(part)
        if (item["type"] == "markup") validateMarkup(item)
    }
}

private fun validateMarkup(markup: Map<String, Any?>) {
    if (stringValue(markup["kind"]) in setOf("open", "standalone", "close")) return
    throw Mf2Error("invalid-markup-kind", "Markup kind must be open, standalone, or close.")
}

private fun validateSelectorAnnotations(declarations: List<Map<String, Any?>>, selectors: List<Map<String, Any?>>) {
    val annotations = selectorAnnotations(declarations)
    for (selector in selectors) {
        val name = normalizeStringKey(stringValue(selector["name"]))
        if (!annotations.containsKey(name)) throw Mf2Error(
            "missing-selector-annotation",
            "Selector $$name must reference a declaration with a function.",
        )
    }
}

private fun selectorAnnotations(declarations: List<Map<String, Any?>>): Map<String, SelectorAnnotation> {
    val aliases = mutableMapOf<String, MutableList<String>>()
    val annotations = linkedMapOf<String, SelectorAnnotation>()
    for (declaration in declarations) {
        val name = normalizeStringKey(stringValue(declaration["name"]))
        val expression = asMap(declaration["value"])
        val function = expression["function"] as? Map<String, Any?>
        if (function != null) annotations[name] = SelectorAnnotation.from(function)
        else {
            val arg = asMap(expression["arg"])
            if (arg["type"] == "variable") aliases.getOrPut(normalizeStringKey(stringValue(arg["name"]))) { mutableListOf() }.add(name)
        }
    }
    val pending = ArrayDeque(annotations.keys)
    while (pending.isNotEmpty()) {
        val source = pending.removeFirst()
        for (alias in aliases[source].orEmpty()) if (alias !in annotations) {
            annotations[alias] = annotations.getValue(source)
            pending.addLast(alias)
        }
    }
    return annotations
}

private fun modelDeclarations(model: Mf2Model): List<Map<String, Any?>> = asList(model["declarations"]).map(::asMap)

private fun modelSelectors(model: Mf2Model): List<Map<String, Any?>> = asList(model["selectors"]).map(::asMap)

private fun modelVariants(model: Mf2Model): List<Map<String, Any?>> = asList(model["variants"]).map(::asMap)

private fun variantKeys(variant: Map<String, Any?>): List<Map<String, Any?>> = asList(variant["keys"]).map(::asMap)

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

private fun selectionKey(
    locale: String,
    annotation: SelectorAnnotation?,
    resolvedValue: ResolvedValue,
    optionValue: (String, String?) -> String?,
): String? {
    if (annotation == null || !annotation.isNumeric || annotation.numberSelect == "exact") return null
    val functionName = stringValue(annotation.function["name"])
    val rendered = valueToString(resolvedValue.rawValue)
    if (functionName == "offset") {
        return selectPluralCategory(locale, rendered, annotation.numberSelect)
    }
    val sourceValue = resolvedValue.source?.let(::numericSourceOperand) ?: rendered
    val sourceNumber = Mf2PortableFunctions.parseDecimalNumber(sourceValue) ?: return null
    val minimum = optionValue("minimumFractionDigits", "0") ?: "0"
    val maximum = optionValue("maximumFractionDigits", null)
    val operand = Mf2UnlocalizedNumericFunctions.selectionOperand(
        sourceNumber,
        functionName,
        Mf2PortableFunctions.parseNonNegativeOption(
            minimum,
            "minimumFractionDigits option must be a non-negative integer.",
        ),
        maximum?.let {
            Mf2PortableFunctions.parseNonNegativeOption(
                it,
                "maximumFractionDigits option must be a non-negative integer.",
            )
        },
    )
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
    return if (functionRef != null) ":${functionRef["name"]}" else ""
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

private data class BidiState(val direction: String?, val force: Boolean, val resolvedDirection: String?)

private fun bidiDirectionFromSource(source: FunctionSource?): String? = source?.cache?.direction

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

private fun formatNumberValue(value: Double): String =
    if (value.isFinite() && value == truncate(value)) java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() else value.toString()

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
    val forceIsolation: Boolean = false,
    val resolvedDirection: String? = source?.cache?.resolvedDirection ?: direction,
)

private data class SelectorValue(
    val rendered: String,
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
            return SelectorAnnotation(functionRef, if (select in setOf("ordinal", "exact")) select!! else "plural")
        }
    }
}
