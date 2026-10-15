package com.merchantpay.app.domain

import java.math.BigDecimal

/** Convert user-entered KES into exact integer cents; no floating-point arithmetic. */
object KesAmount {
    private val decimalPattern = Regex("""(0|[1-9][0-9]*)(\.[0-9]{1,2})?""")

    fun parseMinorUnits(input: String): Long? {
        val value = input.trim()
        if (!decimalPattern.matches(value)) return null
        return try {
            BigDecimal(value).movePointRight(2).longValueExact().takeIf { it > 0 }
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun format(minorUnits: Long): String =
        "KES " + BigDecimal.valueOf(minorUnits, 2).toPlainString()
}
