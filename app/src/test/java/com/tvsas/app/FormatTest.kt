package com.tvsas.app

import org.junit.Assert.assertEquals
import org.junit.Test
import com.tvsas.app.util.Format
import java.util.Calendar

class FormatTest {

    @Test
    fun duration() {
        assertEquals("", Format.duration(0))
        assertEquals("0:07", Format.duration(7))
        assertEquals("12:34", Format.duration(754))
        assertEquals("2:34:14", Format.duration(9254))
    }

    @Test
    fun count() {
        assertEquals("999", Format.count(999))
        assertEquals("1,2 тыс.", Format.count(1234))
        assertEquals("32 тыс.", Format.count(31753))
        assertEquals("1,2 млн", Format.count(1_200_000))
    }

    @Test
    fun date() {
        val now2026 = Calendar.getInstance().apply { set(Calendar.YEAR, 2026) }
        assertEquals("13 сен", Format.date("2026-09-13T12:00:00.000000Z", now2026))
        assertEquals("2 янв 2025", Format.date("2025-01-02T12:00:00.000000Z", now2026))
        assertEquals("", Format.date("garbage", now2026))
    }
}
