package com.lanfetch.app.download

import java.util.Locale

/** Small formatting helpers shared by the UI and notifications. */
object Format {
    private val units = listOf("B", "KB", "MB", "GB", "TB")

    fun bytes(n: Long): String {
        if (n < 0) return "—"
        var value = n.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (index == 0) "$n B" else String.format(Locale.US, "%.2f %s", value, units[index])
    }

    fun speed(bps: Double): String = if (bps <= 0) "" else "${bytes(bps.toLong())}/s"

    fun eta(seconds: Long): String = when {
        seconds < 0 -> ""
        seconds < 60 -> "${seconds}s left"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s left"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m left"
    }
}
