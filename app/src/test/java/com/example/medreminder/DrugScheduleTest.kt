package com.example.medreminder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrugScheduleTest {

    private val drug = Drug(
        id = 1,
        name = "测试药品",
        schedules = listOf(
            DoseSchedule(1, listOf(ReminderTime(8, 0), ReminderTime(8, 15))),
            DoseSchedule(2, listOf(ReminderTime(20, 0)))
        ),
        intervalDays = 2,
        repeatMinutes = 10,
        enabled = true,
        startDate = "2026-08-23"
    )

    @Test
    fun intervalScheduleUsesCalendarDays() {
        assertTrue(drug.isScheduledOn("2026-08-23"))
        assertFalse(drug.isScheduledOn("2026-08-24"))
        assertTrue(drug.isScheduledOn("2026-08-25"))
        assertFalse(drug.isScheduledOn("2026-08-22"))
    }

    @Test
    fun invalidReminderTimeIsRejected() {
        assertTrue(ReminderTime.from("23:59") != null)
        assertTrue(ReminderTime.from("24:00") == null)
        assertTrue(ReminderTime.from("12:60") == null)
    }
}
