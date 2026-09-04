package com.example.core.ledger

import org.json.JSONObject
import kotlin.math.abs

/**
 * MONEY UNIT BOUNDARY
 *
 * uTower source values, raw IQD inputs, and UI shorthand inputs represent different
 * input representations, NOT different persisted runtime monetary units.
 *
 * - parseUtowerAmount(): Normalizes uTower unit values (e.g. 15 -> 15,000 IQD).
 * - parseRawIqd(): Preserves already-whole IQD amounts without scaling.
 * - parseUiThousandsAmount(): Normalizes user UI shorthand ("50k" -> 50,000 IQD).
 *
 * All values persisted in Room or used in BalanceCalculator calculations represent whole IQD.
 */
object MoneyParser {

    private val NON_NUMERIC_REGEX = Regex("[^0-9.-eE]")

    /**
     * Extracts a numeric amount from JSON objects using a list of potential keys.
     */
    fun parseAmount(vararg jsons: JSONObject?, keys: List<String>): Double? {
        for (json in jsons) {
            if (json == null) continue
            for (key in keys) {
                if (json.has(key) && !json.isNull(key)) {
                    val value = json.opt(key)
                    if (value is Number) return value.toDouble()
                    if (value is String) {
                        val cleanStr = value.replace(",", "").replace(NON_NUMERIC_REGEX, "")
                        val d = cleanStr.toDoubleOrNull()
                        if (d != null && !d.isNaN()) return d
                    }
                }
            }
        }
        return null
    }

    /**
     * Parses uTower unit values, mapping e.g., 15 -> 15,000, 40 -> 40,000.
     * Never uses magnitude or conditional guessing.
     */
    fun parseUtowerAmount(value: Double?): Double {
        if (value == null || value.isNaN()) return 0.0
        return value * 1000.0
    }

    /**
     * Parses raw IQD amounts directly as-is without any scaling or guessing.
     */
    fun parseRawIqd(value: Double?): Double {
        if (value == null || value.isNaN()) return 0.0
        return value
    }

    fun parseRawIqdString(input: String?): Double? {
        if (input.isNullOrBlank()) return null
        val cleanStr = input.trim()
            .replace(",", "")
            .replace("IQD", "", ignoreCase = true)
            .replace("iqd", "", ignoreCase = true)
            .replace("د.ع", "")
            .trim()
        return cleanStr.toDoubleOrNull()
    }

    /**
     * Explicitly parses UI subscription / renewal prices, with full support for decimals
     * (e.g. "22.5" -> 22,500 IQD, "35" -> 35,000 IQD, "22500" -> 22,500 IQD, "35k" -> 35,000 IQD).
     */
    fun parseSubscriptionPriceIqd(input: String?): Long? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim().replace(",", "")
        if (trimmed.isEmpty()) return null

        val cleanStr = trimmed
            .replace("IQD", "", ignoreCase = true)
            .replace("iqd", "", ignoreCase = true)
            .replace("د.ع", "")
            .trim()

        if (cleanStr.isEmpty()) return null

        val cleanNoK = if (cleanStr.endsWith("k", ignoreCase = true)) {
            cleanStr.dropLast(1).trim()
        } else {
            cleanStr
        }

        val doubleVal = cleanNoK.toDoubleOrNull() ?: return null
        if (doubleVal < 0.0) return null

        // If entered value is in thousands (e.g. 22.5, 35, 40), scale by 1000: 22.5 * 1000 = 22,500
        // If entered value is full IQD (e.g. 22500, 35000), keep as 22,500
        return if (doubleVal < 1000.0) {
            kotlin.math.round(doubleVal * 1000.0).toLong()
        } else {
            kotlin.math.round(doubleVal).toLong()
        }
    }

    /**
     * Explicitly parses UI thousands amounts for payment, renewal, and debt inputs.
     * Maps inputs e.g. "50" -> 50,000 IQD, "50k" -> 50,000 IQD, "50000" -> 50,000 IQD.
     */
    fun parseUiThousandsAmount(input: String?): Long? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim().replace(",", "")
        if (trimmed.isEmpty()) return null

        val isNegative = trimmed.startsWith("-")
        val cleanStr = trimmed.replace("-", "")
            .replace("IQD", "", ignoreCase = true)
            .replace("iqd", "", ignoreCase = true)
            .replace("د.ع", "")
            .trim()

        if (cleanStr.isEmpty()) return null

        val cleanNoK = if (cleanStr.endsWith("k", ignoreCase = true)) {
            cleanStr.dropLast(1).trim()
        } else {
            cleanStr
        }

        val doubleVal = cleanNoK.toDoubleOrNull() ?: return null
        val absVal = kotlin.math.abs(doubleVal)

        // If entered value is small (< 1000), e.g. 50, treat as thousands: 50 * 1000 = 50,000
        // If entered value is already in thousands (>= 1000), e.g. 50000, treat as 50,000
        val resultInIqd = if (absVal < 1000.0) {
            (absVal * 1000.0).toLong()
        } else {
            absVal.toLong()
        }

        return if (isNegative) -resultInIqd else resultInIqd
    }

    /**
     * Formats an IQD amount to a full deterministic string suitable for UI input fields.
     * Always outputs the exact raw IQD amount (e.g. 15000, 500000, 1000000) so roundtrips
     * through UI input parsing never lose precision or corrupt values.
     */
    fun formatIqdToUiString(amountIqd: Double): String {
        if (amountIqd == 0.0 || amountIqd.isNaN()) return ""
        val longVal = amountIqd.toLong()
        return if (amountIqd == longVal.toDouble()) {
            longVal.toString()
        } else {
            amountIqd.toString()
        }
    }

    /**
     * Formats an IQD amount for display in the UI with commas (e.g. 15,000, 500,000, 1,000,000).
     */
    fun formatIqdForDisplay(amountIqd: Double): String {
        if (amountIqd.isNaN()) return "0"
        val isWhole = amountIqd == amountIqd.toLong().toDouble()
        return if (isWhole) {
            String.format(java.util.Locale.US, "%,.0f", amountIqd)
        } else {
            String.format(java.util.Locale.US, "%,.2f", amountIqd)
        }
    }
}
