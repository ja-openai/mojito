package com.box.l10n.mojito.mf2

import kotlin.math.abs
import kotlin.math.floor

object Mf2RelativeTimeCore {
    private const val DEFAULT_LOCALE = "en"
    private const val MAX_OPTION_LENGTH = 256
    private const val MAX_OPERAND_LENGTH = 256
    private const val MAX_RELATIVE_TIME_QUANTITY = 1_000_000_000.0
    private val decimalNumberPattern =
        Regex("""-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?""")
    private val precisePolicy = listOf(
        PolicyStep(60.0, Unit.SECOND),
        PolicyStep(3_600.0, Unit.MINUTE),
        PolicyStep(86_400.0, Unit.HOUR),
        PolicyStep(604_800.0, Unit.DAY),
        PolicyStep(2_592_000.0, Unit.WEEK),
        PolicyStep(31_536_000.0, Unit.MONTH),
        PolicyStep(Double.POSITIVE_INFINITY, Unit.YEAR),
    )
    private val compactPolicy = listOf(
        PolicyStep(60.0, Unit.SECOND),
        PolicyStep(3_600.0, Unit.MINUTE),
        PolicyStep(86_400.0, Unit.HOUR),
        PolicyStep(Double.POSITIVE_INFINITY, Unit.DAY),
    )
    private val chatPolicy = listOf(
        PolicyStep(45.0, Unit.SECOND),
        PolicyStep(2_700.0, Unit.MINUTE),
        PolicyStep(79_200.0, Unit.HOUR),
        PolicyStep(604_800.0, Unit.DAY),
        PolicyStep(Double.POSITIVE_INFINITY, Unit.WEEK),
    )

    @JvmStatic
    fun options(): Options.Builder = Options.Builder()

    @JvmStatic
    fun dataFromJson(rawData: Map<String, Any?>): Data {
        try {
            val localeMap = stringMap(rawData["localeMap"])
            val patternSets = array(rawData["patternSets"]).map { patternSetFromJson(it) }
            return Data(localeMap, patternSets)
        } catch (error: RuntimeException) {
            throw missingLocaleData("Relative-time core data has an unsupported shape.")
        }
    }

    @JvmStatic
    fun create(data: Data): Formatter = Formatter(data)

    @JvmStatic
    fun registry(data: Data): Mf2FunctionRegistry = create(data).registry()

    @JvmStatic
    fun format(
        value: Any?,
        data: Data,
        options: Options = Options(),
    ): String = create(data).format(value, options)

    @JvmStatic
    fun formatToParts(
        value: Any?,
        data: Data,
        options: Options = Options(),
    ): List<Mf2Part> = create(data).formatToParts(value, options)

    class Formatter(data: Data) {
        private val localeMap: Map<String, String>
        private val patternSets: Map<String, PatternSet>

        init {
            if (data.localeMap.isEmpty() || data.patternSets.isEmpty()) {
                throw missingLocaleData("Relative-time core data has an unsupported shape.")
            }
            localeMap = data.localeMap.toMap()
            patternSets = data.patternSets
                .filter { it.id.isNotEmpty() && it.data.isNotEmpty() }
                .associateBy { it.id }
            if (patternSets.isEmpty()) {
                throw missingLocaleData("Relative-time core data has an unsupported shape.")
            }
        }

        fun registry(): Mf2FunctionRegistry =
            Mf2FunctionRegistry.portable()
                .withFunction("relativeTime") { call -> formatCall(call) }

        fun format(
            value: Any?,
            options: Options = Options(),
        ): String {
            val seconds = parseFiniteNumber(value)
            val unit = if (options.unit == Unit.AUTO) selectUnit(seconds, options.policy) else options.unit
            val quantity = quantity(seconds, unit)
            val locale = LocaleKey.option(options.locale, DEFAULT_LOCALE)

            if (useRelativeZero(options.policy, options.numeric, seconds)) {
                relativeTerm(locale, options.style, unit, "0")?.let { return it }
            }
            if (options.numeric == Numeric.AUTO) {
                relativeOffset(seconds, unit, quantity)?.let { offset ->
                    relativeTerm(locale, options.style, unit, offset)?.let { return it }
                }
            }

            val direction = if (isNegativeRelativeTime(seconds)) "past" else "future"
            val unitData = unitData(locale, options.style, unit)
            val category = PluralRules.selectCardinal(locale, quantity.toString()) ?: "other"
            val pattern = relativeTimePattern(unitData, locale, options.style, unit, direction, category)
            return pattern.replace("{0}", quantity.toString())
        }

        fun formatToParts(
            value: Any?,
            options: Options = Options(),
        ): List<Mf2Part> = listOf(mapOf("type" to "text", "value" to format(value, options)))

        private fun formatCall(call: Mf2FunctionCall): String =
            format(
                call.rawValue ?: call.value,
                Options(
                    locale = call.locale,
                    style = Style.fromName(call.optionValue("style", "short") ?: "short"),
                    numeric = Numeric.fromName(call.optionValue("numeric", "always") ?: "always"),
                    policy = Policy.fromName(call.optionValue("policy", "precise") ?: "precise"),
                    unit = Unit.fromName(call.optionValue("unit", "auto") ?: "auto"),
                ),
            )

        private fun relativeTerm(
            locale: String,
            style: Style,
            unit: Unit,
            offset: String,
        ): String? = unitData(locale, style, unit).relative[offset]

        private fun relativeTimePattern(
            unitData: UnitData,
            locale: String,
            style: Style,
            unit: Unit,
            direction: String,
            category: String,
        ): String {
            val patterns = if (direction == "past") unitData.past else unitData.future
            return patterns[category] ?: patterns["other"] ?: throw missingLocaleData(
                "Missing relative-time pattern for $locale/${style.key}/${unit.key}/$direction.",
            )
        }

        private fun unitData(
            locale: String,
            style: Style,
            unit: Unit,
        ): UnitData {
            val patternSet = patternSetFor(locale)
            return patternSet.data[style.key]?.get(unit.key) ?: throw missingLocaleData(
                "Missing relative-time unit data for $locale/${style.key}/${unit.key}.",
            )
        }

        private fun patternSetFor(locale: String): PatternSet {
            for (candidate in LocaleKey.lookupChain(locale)) {
                val setId = localeMap[candidate] ?: continue
                return patternSets[setId] ?: throw missingLocaleData("Missing relative-time pattern set $setId.")
            }
            throw missingLocaleData("Missing relative-time locale data for $locale.")
        }
    }

    enum class Style(val key: String) {
        LONG("long"),
        SHORT("short"),
        NARROW("narrow"),
        ;

        companion object {
            fun fromName(value: String): Style =
                when (optionName(value, "style")) {
                    "long" -> LONG
                    "short" -> SHORT
                    "narrow" -> NARROW
                    else -> throw Mf2Error.badOption("style must be one of long, short, narrow.")
                }
        }
    }

    enum class Numeric {
        ALWAYS,
        AUTO,
        ;

        companion object {
            fun fromName(value: String): Numeric =
                when (optionName(value, "numeric")) {
                    "always" -> ALWAYS
                    "auto" -> AUTO
                    else -> throw Mf2Error.badOption("numeric must be one of always, auto.")
                }
        }
    }

    enum class Policy(val steps: List<PolicyStep>) {
        PRECISE(precisePolicy),
        COMPACT(compactPolicy),
        CHAT(chatPolicy),
        ;

        companion object {
            fun fromName(value: String): Policy =
                when (optionName(value, "policy")) {
                    "precise" -> PRECISE
                    "compact" -> COMPACT
                    "chat" -> CHAT
                    else -> throw Mf2Error.badOption("policy must be one of precise, compact, chat.")
                }
        }
    }

    enum class Unit(
        val key: String,
        val seconds: Double,
    ) {
        AUTO("auto", 1.0),
        SECOND("second", 1.0),
        MINUTE("minute", 60.0),
        HOUR("hour", 3_600.0),
        DAY("day", 86_400.0),
        WEEK("week", 604_800.0),
        MONTH("month", 2_592_000.0),
        QUARTER("quarter", 7_776_000.0),
        YEAR("year", 31_536_000.0),
        ;

        companion object {
            fun fromName(value: String): Unit =
                when (optionName(value, "unit")) {
                    "auto" -> AUTO
                    "second" -> SECOND
                    "minute" -> MINUTE
                    "hour" -> HOUR
                    "day" -> DAY
                    "week" -> WEEK
                    "month" -> MONTH
                    "quarter" -> QUARTER
                    "year" -> YEAR
                    else -> throw Mf2Error.badOption(
                        "unit must be one of auto, second, minute, hour, day, week, month, quarter, year.",
                    )
                }
        }
    }

    data class Options(
        val locale: String = DEFAULT_LOCALE,
        val style: Style = Style.SHORT,
        val numeric: Numeric = Numeric.ALWAYS,
        val policy: Policy = Policy.PRECISE,
        val unit: Unit = Unit.AUTO,
    ) {
        class Builder {
            private var locale: String = DEFAULT_LOCALE
            private var style: Style = Style.SHORT
            private var numeric: Numeric = Numeric.ALWAYS
            private var policy: Policy = Policy.PRECISE
            private var unit: Unit = Unit.AUTO

            fun locale(locale: String?) = apply {
                this.locale = locale ?: DEFAULT_LOCALE
            }

            fun style(style: Style?) = apply {
                this.style = style ?: Style.SHORT
            }

            fun numeric(numeric: Numeric?) = apply {
                this.numeric = numeric ?: Numeric.ALWAYS
            }

            fun policy(policy: Policy?) = apply {
                this.policy = policy ?: Policy.PRECISE
            }

            fun unit(unit: Unit?) = apply {
                this.unit = unit ?: Unit.AUTO
            }

            fun build(): Options = Options(
                locale = locale.takeIf { it.isNotEmpty() } ?: DEFAULT_LOCALE,
                style = style,
                numeric = numeric,
                policy = policy,
                unit = unit,
            )
        }
    }

    data class Data(
        val localeMap: Map<String, String>,
        val patternSets: List<PatternSet>,
    )

    data class PatternSet(
        val id: String,
        val data: Map<String, Map<String, UnitData>>,
    )

    data class UnitData(
        val future: Map<String, String> = emptyMap(),
        val past: Map<String, String> = emptyMap(),
        val relative: Map<String, String> = emptyMap(),
    )

    data class PolicyStep(
        val upper: Double,
        val unit: Unit,
    )

    private fun patternSetFromJson(value: Any?): PatternSet {
        val item = obj(value)
        return PatternSet(
            id = string(item["id"]),
            data = patternData(item["data"]),
        )
    }

    private fun patternData(value: Any?): Map<String, Map<String, UnitData>> =
        obj(value).mapValues { (_, rawStyleData) ->
            obj(rawStyleData).mapValues { (_, rawUnitData) -> unitDataFromJson(rawUnitData) }
        }

    private fun unitDataFromJson(value: Any?): UnitData {
        val item = obj(value)
        return UnitData(
            future = stringMapOrEmpty(item["future"]),
            past = stringMapOrEmpty(item["past"]),
            relative = stringMapOrEmpty(item["relative"]),
        )
    }

    private fun parseFiniteNumber(value: Any?): Double {
        if (value == null || value is Boolean) {
            throw Mf2Error.badOperand("Relative-time core requires a finite numeric value.")
        }
        val parsed = when (value) {
            is Number -> value.toDouble()
            else -> {
                val text = value.toString().trim()
                if (text.length > MAX_OPERAND_LENGTH) {
                    throw Mf2Error.badOperand("Relative-time core requires a finite numeric value.")
                }
                text.takeIf { it.isNotEmpty() && decimalNumberPattern.matches(it) }?.toDoubleOrNull()
            }
        }
        if (parsed == null || !parsed.isFinite()) {
            throw Mf2Error.badOperand("Relative-time core requires a finite numeric value.")
        }
        return parsed
    }

    private fun optionName(value: String, name: String): String {
        if (value.length > MAX_OPTION_LENGTH) {
            throw Mf2Error.badOption("$name must not exceed 256 characters.")
        }
        return value
    }

    private fun selectUnit(seconds: Double, policy: Policy): Unit {
        val absolute = abs(seconds)
        return policy.steps.firstOrNull { absolute < it.upper }?.unit ?: Unit.YEAR
    }

    private fun quantity(seconds: Double, unit: Unit): Int {
        val absolute = abs(seconds)
        if (absolute == 0.0) {
            return 0
        }
        val quantity = floor(absolute / unit.seconds + 0.5).coerceAtLeast(1.0)
        if (quantity > MAX_RELATIVE_TIME_QUANTITY) {
            throw Mf2Error.badOperand("Relative-time core quantity is outside the supported range.")
        }
        return quantity.toInt()
    }

    private fun useRelativeZero(
        policy: Policy,
        numeric: Numeric,
        seconds: Double,
    ): Boolean = policy == Policy.CHAT && numeric == Numeric.AUTO && abs(seconds) < 45

    private fun relativeOffset(seconds: Double, unit: Unit, quantity: Int): String? =
        when {
            quantity == 0 -> "0"
            abs(seconds) != quantity * unit.seconds -> null
            isNegativeRelativeTime(seconds) -> "-$quantity"
            else -> quantity.toString()
        }

    private fun isNegativeRelativeTime(seconds: Double): Boolean =
        seconds < 0 || seconds.toRawBits() == (-0.0).toRawBits()

    private fun stringMapOrEmpty(value: Any?): Map<String, String> =
        value?.let { stringMap(it) } ?: emptyMap()

    private fun stringMap(value: Any?): Map<String, String> =
        obj(value).mapValues { (_, rawValue) -> string(rawValue) }

    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): Map<String, Any?> =
        value as? Map<String, Any?> ?: throw IllegalArgumentException("Expected JSON object.")

    @Suppress("UNCHECKED_CAST")
    private fun array(value: Any?): List<Any?> =
        value as? List<Any?> ?: throw IllegalArgumentException("Expected JSON array.")

    private fun string(value: Any?): String =
        value as? String ?: throw IllegalArgumentException("Expected JSON string.")

    private fun missingLocaleData(message: String): Mf2Error =
        Mf2Error("missing-locale-data", message)
}
