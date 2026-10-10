package com.example.ui.screens

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Provider-neutral display rules for the subscriber detail screen.
 *
 * These are pure functions so they can be verified headlessly (JVM tier) instead of through a
 * Compose instrumentation run.
 */
object SubscriberDisplayRules {

    private val BAGHDAD: TimeZone = TimeZone.getTimeZone("Asia/Baghdad")

    /**
     * Renders a provider expiry as a short Baghdad-local stamp: `yyyy-MM-dd h:mm a`.
     *
     * Providers send different ISO-8601 variants (`...Z`, fractional seconds, `+03:00` offsets, or
     * no zone at all), and a value that fails to parse must never leak a raw wire string to the
     * screen. A value that is already a formatted stamp is treated as Baghdad-local so it is not
     * shifted a second time.
     */
    fun formatSubscriberExpiration(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.equals("N/A", ignoreCase = true)) return null

        parseInstantMillis(text)?.let { millis ->
            val out = SimpleDateFormat("yyyy-MM-dd h:mm a", Locale.US)
            out.timeZone = BAGHDAD
            return out.format(Date(millis))
        }

        // Unparseable: hand back a trimmed raw value rather than nothing, so the operator still
        // sees what the provider said instead of a blank row.
        return text
    }

    /**
     * Parses the wire formats both providers actually emit into epoch millis.
     *
     * Zoned forms are anchored to UTC (an offset-less form is read as UTC, matching how the
     * providers stamp their timestamps); the already-formatted local form is read as Baghdad.
     */
    fun parseInstantMillis(raw: String): Long? {
        // Drop sub-second precision before parsing: the display is minute-resolution, and
        // SimpleDateFormat cannot consume the six fractional digits the live server emits
        // (.455598) regardless of how many S pattern letters are used.
        val text = stripFraction(raw.trim()).trim()
        if (text.isEmpty()) return null

        // Already rendered as a Baghdad-local stamp: read it back without re-shifting.
        if (LOCAL_STAMP.matches(text)) {
            return parseWith(text, "yyyy-MM-dd HH:mm:ss", BAGHDAD)
        }

        val utc = TimeZone.getTimeZone("UTC")
        // Each family is matched in full before parsing: SimpleDateFormat.parse happily accepts a
        // prefix, which would silently mis-read one wire shape as another.
        if (ISO_OFFSET.matches(text)) return parseWith(text, "yyyy-MM-dd'T'HH:mm:ssXXX", utc)
        if (ISO_PLAIN.matches(text)) return parseWith(text, "yyyy-MM-dd'T'HH:mm:ss", utc)
        return null
    }

    /**
     * Reconciles the provider's own status with the expiry the same provider reported.
     *
     * Alamiry answers `active` for subscribers whose expiry has already passed, so an active
     * answer whose expiry is in the past is demoted to `expired`. Any other provider status is
     * returned verbatim (lower-cased), because the provider remains the authority for its own
     * lifecycle decisions.
     */
    fun effectiveAccountStatus(
        providerStatus: String?,
        expirationMillis: Long?,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        val status = providerStatus?.trim()?.lowercase(Locale.US).orEmpty()
        if (status.isEmpty()) return null
        val expired = expirationMillis != null && expirationMillis <= nowMs
        return if (status == "active" && expired) "expired" else status
    }

    /**
     * Renders a live-session length as compact `Nh Mm`.
     *
     * [seconds] is RADIUS `Acct-Session-Time`, which RFC 2865 defines in seconds, and SAMM's
     * sibling usage payloads agree (`today.secs`, `uptime_used_seconds`). No magnitude-based
     * millisecond fallback is applied: the two units cannot be told apart reliably (2h15m in
     * milliseconds, 8_100_000, is smaller than a 94-day session expressed in seconds), so a
     * heuristic would silently corrupt long sessions.
     */
    fun formatOnlineDuration(seconds: Long?): String? {
        if (seconds == null || seconds < 0) return null
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private val LOCAL_STAMP = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
    private val ISO_OFFSET = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:[+-]\d{2}:\d{2}|Z)""")
    private val ISO_PLAIN = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}""")

    /** Removes the sub-second part of an ISO timestamp, whatever its digit count. */
    private fun stripFraction(text: String): String {
        val dot = text.indexOf('.')
        if (dot < 0) return text
        var end = dot + 1
        while (end < text.length && text[end].isDigit()) end++
        return text.substring(0, dot) + text.substring(end)
    }

    private fun parseWith(text: String, pattern: String, zone: TimeZone): Long? = try {
        val fmt = SimpleDateFormat(pattern, Locale.US)
        fmt.isLenient = false
        fmt.timeZone = zone
        fmt.parse(text)?.time
    } catch (_: Exception) {
        null
    }
}