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
        val text = raw.trim()
        if (text.isEmpty()) return null

        // Already rendered as a Baghdad-local stamp: read it back without re-shifting.
        if (LOCAL_STAMP.matches(text)) {
            return parseWith(text, "yyyy-MM-dd HH:mm:ss", BAGHDAD)
        }

        val utc = TimeZone.getTimeZone("UTC")
        // Each family is matched in full before parsing: SimpleDateFormat.parse happily accepts a
        // prefix, which would silently mis-read one wire shape as another.
        if (ISO_MILLIS_Z.matches(text)) return parseWith(text, "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", utc)
        if (ISO_OFFSET.matches(text)) return parseWith(text, "yyyy-MM-dd'T'HH:mm:ssXXX", utc)
        if (ISO_MILLIS.matches(text)) return parseWith(text, "yyyy-MM-dd'T'HH:mm:ss.SSS", utc)
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

    /** Renders a live-session length as compact `Nh Mm`. */
    fun formatOnlineDuration(seconds: Long?): String? {
        if (seconds == null || seconds < 0) return null
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private val LOCAL_STAMP = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
    private val ISO_MILLIS_Z = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")
    private val ISO_OFFSET = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:[+-]\d{2}:\d{2}|Z)""")
    private val ISO_MILLIS = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+""")
    private val ISO_PLAIN = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}""")

    private fun parseWith(text: String, pattern: String, zone: TimeZone): Long? = try {
        val fmt = SimpleDateFormat(pattern, Locale.US)
        fmt.isLenient = false
        fmt.timeZone = zone
        fmt.parse(text)?.time
    } catch (_: Exception) {
        null
    }
}