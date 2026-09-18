package com.tvsas.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object Format {

    /** 9254 -> "2:34:14", 754 -> "12:34". */
    fun duration(totalSec: Int): String {
        if (totalSec <= 0) return ""
        val h = totalSec / 3600
        val m = totalSec % 3600 / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** 31753 -> "32 тыс.", 1200000 -> "1,2 млн". */
    fun count(n: Int): String = when {
        n >= 1_000_000 -> String.format(Locale("ru"), "%.1f млн", n / 1_000_000.0)
        n >= 10_000 -> String.format(Locale("ru"), "%.0f тыс.", n / 1_000.0)
        n >= 1_000 -> String.format(Locale("ru"), "%.1f тыс.", n / 1_000.0)
        else -> n.toString()
    }

    private val isoParser: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    private val months = arrayOf(
        "янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек",
    )

    /** "2026-09-13T17:33:00.000000Z" -> "13 сен 2026" (or "13 сен" for the current year). */
    fun date(iso: String, now: Calendar = Calendar.getInstance()): String {
        if (iso.length < 19) return ""
        val d = runCatching { isoParser.parse(iso.substring(0, 19)) }.getOrNull() ?: return ""
        val c = Calendar.getInstance().apply { time = d }
        val day = c.get(Calendar.DAY_OF_MONTH)
        val mon = months[c.get(Calendar.MONTH)]
        return if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "$day $mon"
        else "$day $mon ${c.get(Calendar.YEAR)}"
    }
}
