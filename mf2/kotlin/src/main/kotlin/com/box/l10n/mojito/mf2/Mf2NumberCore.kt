package com.box.l10n.mojito.mf2

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Locale

object Mf2NumberCore {
    private const val DEFAULT_LOCALE = "en-US"
    private const val ABSENT_OPTION = "\u0000__mojito_mf2_absent__"
    private const val MAX_OPTION_LENGTH = 256
    private const val MAX_OPERAND_LENGTH = 256
    private val maxAbsoluteFormatValue = BigDecimal("1e21")
    private val decimalText = Regex("^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$")
    private val currencyCode = Regex("^[A-Za-z]{3}$")
    private val numberPattern = Regex("[#0,.]+")

    @JvmStatic
    fun options(): Options.Builder = Options.Builder()

    @JvmStatic
    fun registry(): Mf2FunctionRegistry =
        Mf2FunctionRegistry.portable()
            .withFunction("number") { formatCall(it, Style.NUMBER) }
            .withFunction("integer") { formatCall(it, Style.INTEGER) }
            .withFunction("percent") { formatCall(it, Style.PERCENT) }
            .withFunction("currency") { formatCall(it, Style.CURRENCY) }

    @JvmStatic
    fun format(value: Any?, options: Options = Options()): String {
        val locale = LocaleKey.option(options.locale, DEFAULT_LOCALE)
        val localeData = resolveLocaleData(locale)
        val parsed = parseFiniteNumber(value)
            ?: throw Mf2Error.badOperand("Number core requires a finite numeric value.")

        val currency = if (options.style == Style.CURRENCY) parseCurrency(options.currency) else null
        val pattern = patternForStyle(localeData, options.style)
        val fraction = fractionOptions(options.style, currency, options, pattern)
        val normalized = if (options.style == Style.INTEGER) parsed.withValue(BigDecimal(parsed.value.toBigInteger())) else parsed
        val scaled = if (options.style == Style.PERCENT) normalized.multiply(BigDecimal.valueOf(100)) else normalized
        ensureSupportedMagnitude(scaled.value)
        val formatted = formatDecimal(scaled.value.abs(), localeData, pattern, fraction, options)

        return when (options.style) {
            Style.PERCENT -> applySignedPattern(
                pattern,
                formatted,
                scaled.isNegative(),
                localeData.symbols,
                options.signDisplay,
                percentSign = localeData.symbols["percentSign"],
            )
            Style.CURRENCY -> applySignedPattern(
                pattern,
                formatted,
                scaled.isNegative(),
                localeData.symbols,
                options.signDisplay,
                currency = currencyDisplay(localeData, currency ?: "", options.currencyDisplay),
            )
            Style.NUMBER, Style.INTEGER -> applySign(formatted, scaled.isNegative(), localeData.symbols, options.signDisplay)
        }
    }

    @JvmStatic
    fun formatToParts(value: Any?, options: Options = Options()): List<Mf2Part> =
        listOf(mapOf("type" to "text", "value" to format(value, options)))

    private fun formatCall(call: Mf2FunctionCall, style: Style): String =
        format(
            callNumberValue(call, style),
            Options(
                locale = call.locale,
                style = style,
                currency = currencyOption(call, style),
                currencyDisplay = currencyDisplayOption(call.optionValue("currencyDisplay", "symbol") ?: "symbol"),
                useGrouping = booleanOption(call.optionValue("useGrouping", "true") ?: "true", "useGrouping"),
                minimumFractionDigits = integerOption(
                    call.optionValue("minimumFractionDigits", null),
                    "minimumFractionDigits",
                ),
                maximumFractionDigits = integerOption(
                    call.optionValue("maximumFractionDigits", null),
                    "maximumFractionDigits",
                ),
                signDisplay = signDisplayOption(call.optionValue("signDisplay", "auto") ?: "auto"),
            ),
        )

    private fun currencyOption(call: Mf2FunctionCall, style: Style): String? =
        if (style == Style.CURRENCY) inheritedOptionValue(call, "currency", null) else call.optionValue("currency", null)

    private fun inheritedOptionValue(call: Mf2FunctionCall, name: String, fallback: String?): String? {
        val own = call.optionValue(name, ABSENT_OPTION)
        if (own != ABSENT_OPTION) return own
        var source = call.inheritedSource
        while (source != null) {
            val value = source.optionValue(name, ABSENT_OPTION)
            if (value != ABSENT_OPTION) return value
            source = source.inherited
        }
        return fallback
    }

    private fun callNumberValue(call: Mf2FunctionCall, style: Style): Any? {
        val source = call.inheritedSource
        if (source != null) {
            if (style == Style.NUMBER && source.function["name"] == "integer") {
                parseFiniteNumber(source.value)?.let { return it.withValue(BigDecimal(it.value.toBigInteger())) }
            }
            return source.value
        }
        return call.rawValue ?: call.value
    }

    private fun resolveLocaleData(locale: String): CldrNumberData.LocaleData {
        for (candidate in LocaleKey.lookupChain(locale)) {
            CldrNumberData.locales[candidate]?.let { return it }
            CldrNumberData.locales.values
                .firstOrNull { it.numbersSourceLocale == candidate }
                ?.let { return it }
        }
        return CldrNumberData.locales.getValue(DEFAULT_LOCALE)
    }

    private fun patternForStyle(localeData: CldrNumberData.LocaleData, style: Style): String =
        when (style) {
            Style.PERCENT -> localeData.percentPattern
            Style.CURRENCY -> localeData.currencyPattern
            Style.NUMBER, Style.INTEGER -> localeData.decimalPattern
        }

    private fun fractionOptions(
        style: Style,
        currency: String?,
        options: Options,
        pattern: String,
    ): FractionOptions {
        var defaults = fractionDefaultsFromPattern(pattern)
        if (style == Style.INTEGER) {
            defaults = FractionOptions(0, 0)
        }
        if (style == Style.CURRENCY) {
            val currencyDefaults =
                CldrNumberData.currencyFractions[currency] ?: CldrNumberData.currencyFractions.getValue("DEFAULT")
            defaults = FractionOptions(currencyDefaults.digits, currencyDefaults.digits)
        }
        var minimum = options.minimumFractionDigits ?: defaults.minimum
        var maximum = options.maximumFractionDigits ?: defaults.maximum
        if (minimum < 0 || minimum > Mf2PortableFunctions.MAX_FRACTION_DIGITS) {
            throw Mf2Error.badOption("minimumFractionDigits must be a non-negative integer.")
        }
        if (maximum < 0 || maximum > Mf2PortableFunctions.MAX_FRACTION_DIGITS) {
            throw Mf2Error.badOption("maximumFractionDigits must be a non-negative integer.")
        }
        if (options.minimumFractionDigits != null && options.maximumFractionDigits == null && maximum < minimum) {
            maximum = minimum
        }
        if (options.maximumFractionDigits != null && options.minimumFractionDigits == null && maximum < minimum) {
            minimum = maximum
        }
        if (maximum < minimum) {
            throw Mf2Error.badOption("maximumFractionDigits must be greater than or equal to minimumFractionDigits.")
        }
        return FractionOptions(minimum, maximum)
    }

    private fun fractionDefaultsFromPattern(pattern: String): FractionOptions {
        val numberPattern = numberPattern(pattern)
        val dot = numberPattern.indexOf('.')
        if (dot < 0) {
            return FractionOptions(0, 0)
        }
        val fraction = numberPattern.substring(dot + 1)
        return FractionOptions(fraction.count { it == '0' }, fraction.length)
    }

    private fun formatDecimal(
        value: BigDecimal,
        localeData: CldrNumberData.LocaleData,
        pattern: String,
        fraction: FractionOptions,
        options: Options,
    ): String {
        val rounded = value.setScale(fraction.maximum, RoundingMode.HALF_UP).toPlainString()
        val dot = rounded.indexOf('.')
        var integer = if (dot < 0) rounded else rounded.substring(0, dot)
        var decimal = if (dot < 0) "" else rounded.substring(dot + 1)
        while (decimal.length > fraction.minimum && decimal.endsWith("0")) {
            decimal = decimal.dropLast(1)
        }
        while (decimal.length < fraction.minimum) {
            decimal += "0"
        }

        val grouping = groupingInfo(pattern)
        if (options.useGrouping && shouldGroup(integer, grouping, localeData.minimumGroupingDigits)) {
            integer = groupInteger(integer, grouping, localeData.symbols.getValue("group"))
        }
        integer = localizeDigits(integer, localeData.numberingSystemDigits)
        if (decimal.isNotEmpty()) {
            return integer + localeData.symbols.getValue("decimal") +
                localizeDigits(decimal, localeData.numberingSystemDigits)
        }
        return integer
    }

    private fun groupingInfo(pattern: String): GroupingInfo {
        val integerPattern = numberPattern(pattern).split(".", limit = 2)[0]
        val groups = integerPattern.split(",")
        if (groups.size == 1) {
            return GroupingInfo(0, 0)
        }
        val primary = placeholderCount(groups.last())
        val secondary = if (groups.size > 2) placeholderCount(groups[groups.size - 2]) else primary
        return GroupingInfo(primary, secondary)
    }

    private fun shouldGroup(integer: String, grouping: GroupingInfo, minimumGroupingDigits: Int): Boolean {
        if (grouping.primary <= 0) {
            return false
        }
        return integer.length >= grouping.primary + minimumGroupingDigits
    }

    private fun groupInteger(integer: String, grouping: GroupingInfo, separator: String): String {
        val output = StringBuilder()
        var end = integer.length
        var size = grouping.primary
        while (end > 0) {
            val start = (end - size).coerceAtLeast(0)
            if (output.isNotEmpty()) {
                output.insert(0, separator)
            }
            output.insert(0, integer.substring(start, end))
            end = start
            size = if (grouping.secondary == 0) grouping.primary else grouping.secondary
        }
        return output.toString()
    }

    private fun applySign(
        formatted: String,
        negative: Boolean,
        symbols: Map<String, String>,
        signDisplay: SignDisplay,
    ): String {
        if (signDisplay == SignDisplay.NEVER) {
            return formatted
        }
        if (negative) {
            return symbols.getValue("minusSign") + formatted
        }
        if (signDisplay == SignDisplay.ALWAYS) {
            return symbols.getValue("plusSign") + formatted
        }
        return formatted
    }

    private fun applyPattern(
        pattern: String,
        formatted: String,
        percentSign: String? = null,
        currency: String? = null,
    ): String {
        var output = numberPattern.replaceFirst(pattern, formatted)
        if (percentSign != null) {
            output = output.replace("%", percentSign)
        }
        if (currency != null) {
            output = output.replace("¤", currency)
        }
        return output
    }

    private fun applySignedPattern(
        pattern: String,
        formatted: String,
        negative: Boolean,
        symbols: Map<String, String>,
        signDisplay: SignDisplay,
        percentSign: String? = null,
        currency: String? = null,
    ): String {
        val separator = pattern.indexOf(';')
        val positivePattern = if (separator < 0) pattern else pattern.substring(0, separator)
        if (negative && signDisplay != SignDisplay.NEVER) {
            if (separator >= 0) {
                return applyPattern(pattern.substring(separator + 1), formatted, percentSign, currency)
            }
            return symbols.getValue("minusSign") + applyPattern(positivePattern, formatted, percentSign, currency)
        }
        val output = applyPattern(positivePattern, formatted, percentSign, currency)
        if (signDisplay == SignDisplay.ALWAYS) {
            return symbols.getValue("plusSign") + output
        }
        return output
    }

    private fun currencyDisplay(
        localeData: CldrNumberData.LocaleData,
        currency: String,
        display: CurrencyDisplay,
    ): String {
        if (display == CurrencyDisplay.CODE) {
            return currencyCodeDisplay(localeData, currency)
        }
        val data = localeData.currencies[currency] ?: return currency
        if (display == CurrencyDisplay.NARROW_SYMBOL && data.narrowSymbol != null) {
            return data.narrowSymbol
        }
        return data.symbol ?: currency
    }

    private fun currencyCodeDisplay(
        localeData: CldrNumberData.LocaleData,
        currency: String,
    ): String {
        val positivePattern = localeData.currencyPattern.split(";", limit = 2)[0]
        val before = if (positivePattern.contains("#\u00a4") || positivePattern.contains("0\u00a4")) {
            localeData.currencySpacing.beforeCurrency
        } else {
            ""
        }
        val after = if (positivePattern.contains("\u00a4#") || positivePattern.contains("\u00a40")) {
            localeData.currencySpacing.afterCurrency
        } else {
            ""
        }
        return before + currency + after
    }

    private fun parseFiniteNumber(value: Any?): ParsedNumber? =
        when (value) {
            is ParsedNumber -> value
            is BigDecimal -> ParsedNumber(value, value.signum() < 0)
            is BigInteger -> ParsedNumber(BigDecimal(value), value.signum() < 0)
            is Number -> {
                val doubleValue = value.toDouble()
                if (!java.lang.Double.isFinite(doubleValue)) null else ParsedNumber(
                    BigDecimal.valueOf(doubleValue),
                    java.lang.Double.compare(doubleValue, 0.0) < 0,
                )
            }
            else -> {
                val text = try {
                    value?.toString()?.trim().orEmpty()
                } catch (error: RuntimeException) {
                    return null
                }
                if (text.length > MAX_OPERAND_LENGTH || !decimalText.matches(text)) {
                    null
                } else {
                    val decimal = BigDecimal(text)
                    ParsedNumber(decimal, decimal.signum() < 0 || (decimal.signum() == 0 && text.startsWith("-")))
                }
            }
        }

    private fun ensureSupportedMagnitude(value: BigDecimal) {
        if (value.abs() >= maxAbsoluteFormatValue) {
            throw Mf2Error.badOperand("Number core numeric value is outside the supported magnitude.")
        }
    }

    private fun parseCurrency(value: String?): String {
        if (value == null) {
            throw Mf2Error.badOption("currency must be a three-letter ISO 4217 code.")
        }
        val text = optionName(value, "currency")
        if (!currencyCode.matches(text)) {
            throw Mf2Error.badOption("currency must be a three-letter ISO 4217 code.")
        }
        return text.uppercase(Locale.ROOT)
    }

    private fun integerOption(value: String?, name: String): Int? {
        if (value == null) {
            return null
        }
        val text = optionName(value, name)
        if (text.isEmpty() || !text.all { it in '0'..'9' }) {
            throw Mf2Error.badOption("$name must be a non-negative integer.")
        }
        return text.toIntOrNull() ?: throw Mf2Error.badOption("$name must be a non-negative integer.")
    }

    private fun signDisplayOption(value: String): SignDisplay =
        when (optionName(value, "signDisplay")) {
            "auto" -> SignDisplay.AUTO
            "always" -> SignDisplay.ALWAYS
            "never" -> SignDisplay.NEVER
            else -> throw Mf2Error.badOption("signDisplay must be auto, always, or never.")
        }

    private fun currencyDisplayOption(value: String): CurrencyDisplay =
        when (optionName(value, "currencyDisplay")) {
            "symbol" -> CurrencyDisplay.SYMBOL
            "narrowSymbol" -> CurrencyDisplay.NARROW_SYMBOL
            "code" -> CurrencyDisplay.CODE
            else -> throw Mf2Error.badOption("currencyDisplay must be symbol, narrowSymbol, or code.")
        }

    private fun booleanOption(value: String, name: String): Boolean =
        when (optionName(value, name)) {
            "true" -> true
            "false" -> false
            else -> throw Mf2Error.badOption("$name must be true or false.")
        }

    private fun optionName(value: String, name: String): String {
        if (value.length > MAX_OPTION_LENGTH) {
            throw Mf2Error.badOption("$name must not exceed 256 characters.")
        }
        return value
    }

    private fun numberPattern(pattern: String): String =
        numberPattern.find(pattern)?.value.orEmpty()

    private fun placeholderCount(pattern: String): Int =
        pattern.count { it == '#' || it == '0' }

    private fun localizeDigits(value: String, digits: String?): String {
        if (digits == null || digits == "0123456789") {
            return value
        }
        val output = StringBuilder(value.length)
        for (ch in value) {
            if (ch in '0'..'9') {
                output.append(digits[ch - '0'])
            } else {
                output.append(ch)
            }
        }
        return output.toString()
    }

    enum class Style {
        NUMBER,
        INTEGER,
        PERCENT,
        CURRENCY,
    }

    enum class CurrencyDisplay {
        SYMBOL,
        NARROW_SYMBOL,
        CODE,
    }

    enum class SignDisplay {
        AUTO,
        ALWAYS,
        NEVER,
    }

    private data class ParsedNumber(
        val value: BigDecimal,
        val negative: Boolean,
    ) {
        fun withValue(nextValue: BigDecimal): ParsedNumber =
            ParsedNumber(nextValue, nextValue.signum() < 0 || (nextValue.signum() == 0 && negative))

        fun multiply(factor: BigDecimal): ParsedNumber = withValue(value.multiply(factor))

        fun isNegative(): Boolean = value.signum() < 0 || (value.signum() == 0 && negative)
    }

    data class Options(
        val locale: String = DEFAULT_LOCALE,
        val style: Style = Style.NUMBER,
        val currency: String? = null,
        val currencyDisplay: CurrencyDisplay = CurrencyDisplay.SYMBOL,
        val useGrouping: Boolean = true,
        val minimumFractionDigits: Int? = null,
        val maximumFractionDigits: Int? = null,
        val signDisplay: SignDisplay = SignDisplay.AUTO,
    ) {
        class Builder {
            private var locale = DEFAULT_LOCALE
            private var style = Style.NUMBER
            private var currency: String? = null
            private var currencyDisplay = CurrencyDisplay.SYMBOL
            private var useGrouping = true
            private var minimumFractionDigits: Int? = null
            private var maximumFractionDigits: Int? = null
            private var signDisplay = SignDisplay.AUTO

            fun locale(value: String?) = apply {
                locale = value ?: DEFAULT_LOCALE
            }

            fun style(value: Style?) = apply {
                style = value ?: Style.NUMBER
            }

            fun currency(value: String?) = apply {
                currency = value
            }

            fun currencyDisplay(value: CurrencyDisplay?) = apply {
                currencyDisplay = value ?: CurrencyDisplay.SYMBOL
            }

            fun useGrouping(value: Boolean) = apply {
                useGrouping = value
            }

            fun minimumFractionDigits(value: Int?) = apply {
                minimumFractionDigits = value
            }

            fun maximumFractionDigits(value: Int?) = apply {
                maximumFractionDigits = value
            }

            fun signDisplay(value: SignDisplay?) = apply {
                signDisplay = value ?: SignDisplay.AUTO
            }

            fun build(): Options =
                Options(
                    locale = locale,
                    style = style,
                    currency = currency,
                    currencyDisplay = currencyDisplay,
                    useGrouping = useGrouping,
                    minimumFractionDigits = minimumFractionDigits,
                    maximumFractionDigits = maximumFractionDigits,
                    signDisplay = signDisplay,
                )
        }
    }

    private data class FractionOptions(val minimum: Int, val maximum: Int)

    private data class GroupingInfo(val primary: Int, val secondary: Int)
}
