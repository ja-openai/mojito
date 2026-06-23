package com.box.l10n.mojito.mf2

import java.math.BigDecimal
import java.math.RoundingMode
internal object Mf2UnlocalizedNumericFunctions {
    private const val MAX_DECIMAL_OUTPUT_CHARS = 1000

    fun registerFormatters(formatters: MutableMap<String, Mf2FunctionFormatter>) {
        formatters["number"] = ::formatNumber
        formatters["percent"] = ::formatPercent
        formatters["integer"] = ::formatInteger
    }

    private fun formatNumber(call: Mf2FunctionCall): String {
        val message = "Number function requires a numeric operand."
        val value = Mf2PortableFunctions.parseCallDecimalOperand(call, message)
        val minimumFractionDigits = minimumFractionDigits(call)
        val maximumFractionDigits = maximumFractionDigits(call)
        validateFractionDigits(minimumFractionDigits, maximumFractionDigits)
        val rounded = roundDecimalWithMaximumFractionDigits(value, maximumFractionDigits)
        ensureDecimalOutputBounded(rounded, minimumFractionDigits, message)
        return formatDecimalNumber(rounded, Mf2PortableFunctions.signDisplayAlways(call.function), minimumFractionDigits)
    }

    private fun formatPercent(call: Mf2FunctionCall): String {
        val message = "Percent function requires a numeric operand."
        val value = Mf2PortableFunctions.parseCallDecimalOperand(call, message)
        val minimumFractionDigits = minimumFractionDigits(call)
        val maximumFractionDigits = maximumFractionDigits(call)
        validateFractionDigits(minimumFractionDigits, maximumFractionDigits)
        val percentValue = roundDecimalWithMaximumFractionDigits(value.movePointRight(2), maximumFractionDigits)
        ensureDecimalOutputBounded(percentValue, minimumFractionDigits, message)
        var formatted = formatDecimalNumber(percentValue, false, 0)
        if (Mf2PortableFunctions.signDisplayAlways(call.function) && value.signum() >= 0) formatted = "+$formatted"
        return appendMinimumFractionDigits(formatted, minimumFractionDigits) + "%"
    }

    private fun formatInteger(call: Mf2FunctionCall): String {
        val message = "Integer function requires a numeric operand."
        val integer = Mf2PortableFunctions.parseCallDecimalOperand(call, message).setScale(0, RoundingMode.DOWN)
        ensureDecimalOutputBounded(integer, 0, message)
        return formatDecimalNumber(integer, Mf2PortableFunctions.signDisplayAlways(call.function), 0)
    }

    private fun formatDecimalNumber(value: Double, signAlways: Boolean, minimumFractionDigits: Int): String {
        return formatDecimalNumber(BigDecimal.valueOf(value), signAlways, minimumFractionDigits)
    }

    private fun formatDecimalNumber(value: BigDecimal, signAlways: Boolean, minimumFractionDigits: Int): String {
        val normalized = normalizeDecimal(value)
        var formatted = normalized.toPlainString()
        if (signAlways && normalized.signum() >= 0) formatted = "+$formatted"
        return appendMinimumFractionDigits(formatted, minimumFractionDigits)
    }

    private fun roundDecimalWithMaximumFractionDigits(value: BigDecimal, maximumFractionDigits: Int?): BigDecimal {
        if (maximumFractionDigits == null) return value
        return value.setScale(maximumFractionDigits, RoundingMode.HALF_UP)
    }

    private fun formatFixedFractionDigits(value: Double, fractionDigits: Int): String =
        BigDecimal.valueOf(value).setScale(fractionDigits, RoundingMode.HALF_UP).toPlainString()

    private fun normalizeDecimal(value: BigDecimal): BigDecimal =
        if (value.signum() == 0) BigDecimal.ZERO else value.stripTrailingZeros()

    private fun ensureDecimalOutputBounded(value: BigDecimal, minimumFractionDigits: Int, message: String) {
        if (estimatedDecimalOutputChars(value, minimumFractionDigits) > MAX_DECIMAL_OUTPUT_CHARS) {
            throw Mf2Error.badOperand(message)
        }
    }

    private fun estimatedDecimalOutputChars(value: BigDecimal, minimumFractionDigits: Int): Int {
        val normalized = normalizeDecimal(value)
        val sign = if (normalized.signum() < 0) 1 else 0
        val precision = normalized.precision()
        val scale = normalized.scale()
        if (scale <= 0) return sign + precision - scale
        val integerDigits = maxOf(precision - scale, 1)
        val fractionDigits = maxOf(scale, minimumFractionDigits)
        return sign + integerDigits + if (fractionDigits > 0) 1 + fractionDigits else 0
    }

    private fun appendMinimumFractionDigits(formatted: String, minimumFractionDigits: Int): String {
        if (minimumFractionDigits == 0) return formatted
        val dot = formatted.indexOf(".")
        val fractionDigits = if (dot < 0) 0 else formatted.length - dot - 1
        val output = StringBuilder(formatted)
        if (fractionDigits == 0) output.append(".")
        for (index in fractionDigits until minimumFractionDigits) output.append("0")
        return output.toString()
    }

    private fun minimumFractionDigits(call: Mf2FunctionCall): Int =
        call.optionValue("minimumFractionDigits", null)
            ?.let { Mf2PortableFunctions.parseNonNegativeOption(it, "minimumFractionDigits option must be a non-negative integer.") }
            ?: 0

    private fun maximumFractionDigits(call: Mf2FunctionCall): Int? =
        call.optionValue("maximumFractionDigits", null)
            ?.let { Mf2PortableFunctions.parseNonNegativeOption(it, "maximumFractionDigits option must be a non-negative integer.") }

    private fun validateFractionDigits(minimumFractionDigits: Int, maximumFractionDigits: Int?) {
        if (maximumFractionDigits != null && minimumFractionDigits > maximumFractionDigits) {
            throw Mf2Error.badOption("maximumFractionDigits must be greater than or equal to minimumFractionDigits.")
        }
    }
}
