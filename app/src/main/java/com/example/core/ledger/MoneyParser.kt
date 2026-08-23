package com.example.core.ledger

import org.json.JSONObject
import kotlin.math.abs

/**
 * Explicit currency parser API as mandated by Phase 8 of the Structural Stabilization Plan.
 */
sealed interface MoneyValue {
    data class Iqd(val rawAmount: Double) : MoneyValue
    data class Thousands(val thousandsCount: Long) : MoneyValue
    object Invalid : MoneyValue

    fun toIqdDouble(): Double {
        return when (this) {
            is Iqd -> rawAmount
            is Thousands -> thousandsCount * 1000.0
            is Invalid -> 0.0
        }
    }

    fun toIqdLong(): Long {
        return when (this) {
            is Iqd -> rawAmount.toLong()
            is Thousands -> thousandsCount * 1000L
            is Invalid -> 0L
        }
    }
}

/**
 * MONEY UNIT BOUNDARY
 *
 * uTower source values, raw IQD inputs, and UI shorthand inputs represent different
 * input representations, NOT different persisted runtime monetary units.
 *
 * - parseUtowerAmount(): Normalizes uTower unit values (e.g. 15 -> 15,000 IQD).
 * - parseRawIqd(): Preserves already-whole IQD amounts without scaling.
 * - parseUiThousandsAmount() / parseUiInput(): Normalizes user UI shorthand ("50k" -> 50,000 IQD).
 *
 * All values persisted in Room or used in BalanceCalculator calculations represent whole IQD.
 */
object MoneyParser {

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
                        val cleanStr = value.replace(",", "").replace(Regex("[^0-9.-eE]"), "")
                        val d = cleanStr.toDoubleOrNull()
                        if (d != null && !d.isNaN()) return d
                    }
                }
            }
        }
        return null
    }

    @Deprecated("Use parseUtowerAmount() for uTower unit values or parseRawIqd() for exact IQD values.", ReplaceWith("parseRawIqd(value)"))
    fun parseIqdAmount(value: Double?): Double {
        if (value == null || value.isNaN()) return 0.0
        return value
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
     * Deterministically parses UI input into a type-safe [MoneyValue].
     * Handles:
     * - 1000 / 5000 / 15000 / 500000 / 1000000 (raw IQD)
     * - 15,000 / 500,000 / 1,000,000 (comma-formatted IQD)
     * - 15000 IQD / 15000 د.ع (explicit unit)
     * - 15k / 15 K / 500k / 1000k (explicit thousands)
     * - 15.0 / 15000.0 (explicit float)
     */
    fun parseUiInput(rawInput: String?): MoneyValue {
        if (rawInput.isNullOrBlank()) return MoneyValue.Invalid

        val trimmed = rawInput.trim()
        val isNegative = trimmed.startsWith("-")
        val cleanUnit = trimmed
            .replace("-", "")
            .replace("IQD", "", ignoreCase = true)
            .replace("iqd", "", ignoreCase = true)
            .replace("د.ع", "")
            .trim()

        if (cleanUnit.isBlank()) return MoneyValue.Invalid

        // Check explicit 'k' or 'K' suffix
        if (cleanUnit.endsWith("k", ignoreCase = true)) {
            val countStr = cleanUnit.dropLast(1).trim().replace(",", "")
            val count = countStr.toLongOrNull() ?: return MoneyValue.Invalid
            return MoneyValue.Thousands(if (isNegative) -count else count)
        }

        // Check for comma formatting or decimal point
        val hasCommas = cleanUnit.contains(",")
        val hasDecimal = cleanUnit.contains(".")

        val numericStr = cleanUnit.replace(",", "")

        if (hasDecimal) {
            val d = numericStr.toDoubleOrNull() ?: return MoneyValue.Invalid
            val valDouble = if (isNegative) -d else d
            return MoneyValue.Iqd(valDouble)
        }

        val rawLong = numericStr.toLongOrNull() ?: return MoneyValue.Invalid
        val signedVal = if (isNegative) -rawLong else rawLong

        // All plain numeric values without 'k' suffix are deterministically treated as exact raw IQD
        return MoneyValue.Iqd(signedVal.toDouble())
    }

    /**
     * Normalizes string input from UI dialogs to an exact IQD value.
     */
    @Deprecated("Use parseUiThousandsAmount() for payment/debt inputs or parseSubscriptionPriceIqd() for subscriptions.", ReplaceWith("parseUiThousandsAmount(rawInput)?.toDouble() ?: 0.0"))
    fun normalizeUiInputToIqd(rawInput: String?): Double {
        return parseUiInput(rawInput).toIqdDouble()
    }

    /**
     * Formats an IQD amount to a full deterministic string suitable for UI input fields.
     * Always outputs the exact raw IQD amount (e.g. 15000, 500000, 1000000) so roundtrips
     * through normalizeUiInputToIqd never lose precision or corrupt values.
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
