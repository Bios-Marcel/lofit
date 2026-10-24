package lofit

import java.text.DecimalFormatSymbols
import java.util.Locale

fun formatWeight(value: Double, locale: Locale = Locale.getDefault()): String =
    value.toBigDecimal().stripTrailingZeros().toPlainString()
        .replace('.', DecimalFormatSymbols.getInstance(locale).decimalSeparator)

fun parseWeight(value: String, locale: Locale = Locale.getDefault()): Double? =
    value.replace(DecimalFormatSymbols.getInstance(locale).decimalSeparator, '.').toDoubleOrNull()
