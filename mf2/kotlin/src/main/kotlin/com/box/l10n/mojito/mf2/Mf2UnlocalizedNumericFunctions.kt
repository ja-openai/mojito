package com.box.l10n.mojito.mf2

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.truncate

internal object Mf2UnlocalizedNumericFunctions {
    fun registerFormatters(formatters: MutableMap<String, Mf2FunctionFormatter>) {
        formatters["number"] = ::formatNumber
        formatters["percent"] = ::formatPercent
        formatters["integer"] = ::formatInteger
    }

    private fun formatNumber(call: Mf2FunctionCall): String {
        val value = Mf2PortableFunctions.parseCallDecimal(call, "Number function requires a numeric operand.")
        return formatDecimalNumber(value, Mf2PortableFunctions.signDisplayAlways(call.function), minimumFractionDigits(call))
    }

    private fun formatPercent(call: Mf2FunctionCall): String {
        val value = Mf2PortableFunctions.parseCallDecimal(call, "Percent function requires a numeric operand.")
        val formatted = formatPercentNumber(
            value,
            Mf2PortableFunctions.signDisplayAlways(call.function),
            minimumFractionDigits(call),
            maximumFractionDigits(call),
        )
        return formatted
    }

    private fun formatInteger(call: Mf2FunctionCall): String {
        val value = Mf2PortableFunctions.parseCallDecimal(call, "Integer function requires a numeric operand.")
        return Mf2PortableFunctions.formatIntegerNumber(
            truncate(value).toLong(),
            Mf2PortableFunctions.signDisplayAlways(call.function),
        )
    }

    private fun formatDecimalNumber(value: Double, signAlways: Boolean, minimumFractionDigits: Int): String {
        var formatted = value.toString()
        if (formatted.endsWith(".0")) formatted = formatted.dropLast(2)
        if (signAlways && value >= 0.0) formatted = "+$formatted"
        return appendMinimumFractionDigits(formatted, minimumFractionDigits)
    }

    private fun formatPercentNumber(
        value: Double,
        signAlways: Boolean,
        minimumFractionDigits: Int,
        maximumFractionDigits: Int?,
    ): String {
        var formatted = formatDecimalWithMaximumFractionDigits(value * 100.0, maximumFractionDigits)
        if (signAlways && value >= 0.0) formatted = "+$formatted"
        return appendMinimumFractionDigits(formatted, minimumFractionDigits) + "%"
    }

    private fun formatDecimalWithMaximumFractionDigits(value: Double, digits: Int?): String {
        if (digits == null) return formatDecimalNumber(value, false, 0)
        var formatted = formatFixedFractionDigits(value, digits)
        while (formatted.contains(".") && formatted.endsWith("0")) formatted = formatted.dropLast(1)
        if (formatted.endsWith(".")) formatted = formatted.dropLast(1)
        return formatted
    }

    private fun formatFixedFractionDigits(value: Double, fractionDigits: Int): String =
        BigDecimal.valueOf(value).setScale(fractionDigits, RoundingMode.HALF_UP).toPlainString()

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
}
