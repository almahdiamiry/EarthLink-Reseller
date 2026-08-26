package com.example.core.ledger

object NoteCleaner {

    private val PREFIXES = listOf(
        "[RENEW_PAY]",
        "[RENEW]",
        "[DEBT]",
        "[DEPOSIT]",
        "[PAYMENT]",
        "[NOTE]",
        "[MEMO]",
        "[LOAN]"
    )

    private val BOILERPLATE_PHRASES = listOf(
        "تجديد اشتراك بقيمة",
        "تجديد اشتراك",
        "تسديد تجديد بقيمة",
        "تسديد تجديد",
        "إضافة دين بقيمة",
        "اضافة دين بقيمة",
        "إضافة دين",
        "اضافة دين",
        "تسديد مبلغ",
        "إيداع مبلغ",
        "ايداع مبلغ",
        "دفع اشتراك",
        "تسديد نقدي",
        "تسديد كاش",
        "Subscription renewal",
        "Added debt",
        "Payment of",
        "Deposit of"
    )

    private val NOISE_WORDS = setOf(
        "iqd", "usd", "id", "dinar", "dollar", "null",
        "د.ع", "دينار", "دولار", "د", "ع", "بقيمة", "قيمة", "مبلغ"
    )

    /**
     * Extracts genuine, user-entered or semantically meaningful note text.
     * Strips system tags, raw amount noise, and redundant auto-generated labels.
     * Returns empty string if the note is pure noise.
     */
    fun extractGenuineNote(rawNote: String?, amountIqd: Double? = null): String {
        if (rawNote.isNullOrBlank() || rawNote.equals("null", ignoreCase = true)) return ""

        var cleaned = rawNote.trim()
        for (prefix in PREFIXES) {
            if (cleaned.startsWith(prefix, ignoreCase = true)) {
                cleaned = cleaned.substring(prefix.length).trim()
            }
        }

        if (cleaned.isEmpty() || cleaned.equals("null", ignoreCase = true)) return ""

        // If the note has multiple segments separated by | or - or :
        val segments = cleaned.split(Regex("""\s*\|\s*|\s+-\s+|\s*:\s*"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (segments.size > 1) {
            val validSegments = segments.filterNot { isNoiseOrRedundant(it, amountIqd) }
            if (validSegments.isEmpty()) return ""
            return validSegments.joinToString(" - ")
        }

        if (isNoiseOrRedundant(cleaned, amountIqd)) {
            return ""
        }

        return cleaned
    }

    /**
     * Checks if a note fragment is just a raw numeric amount, currency text, or system boilerplate.
     */
    fun isNoiseOrRedundant(text: String, amountIqd: Double? = null): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true) || trimmed == "000" || trimmed == "0") {
            return true
        }

        // Exact match with numeric amount formats
        if (amountIqd != null && amountIqd > 0.0) {
            val amtLong = amountIqd.toLong().toString()
            val amtFormatted = MoneyParser.formatIqdForDisplay(amountIqd)
            val amtDoubleStr = amountIqd.toString()
            if (trimmed == amtLong || trimmed == amtFormatted || trimmed == amtDoubleStr || trimmed == "$amtLong.0" || trimmed == "$amtLong.00") {
                return true
            }
        }

        // Extract only letters
        val lettersOnly = extractLettersOnly(trimmed)
        if (lettersOnly.isEmpty()) {
            // Pure numbers or punctuation
            return true
        }

        // Check against boilerplate phrases
        var afterBoilerplate = trimmed
        for (phrase in BOILERPLATE_PHRASES) {
            afterBoilerplate = afterBoilerplate.replace(phrase, "", ignoreCase = true)
        }

        val remainingLetters = extractLettersOnly(afterBoilerplate)
        if (remainingLetters.isEmpty()) {
            return true
        }

        // Check if remaining words are only noise words
        val words = afterBoilerplate.split(Regex("""\s+""")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val nonNoiseWords = words.filterNot { word ->
            NOISE_WORDS.contains(word) || extractLettersOnly(word).isEmpty() || NOISE_WORDS.contains(extractLettersOnly(word))
        }

        return nonNoiseWords.isEmpty()
    }

    private fun extractLettersOnly(input: String): String {
        val sb = StringBuilder()
        for (ch in input) {
            if (ch.isLetter()) {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}
