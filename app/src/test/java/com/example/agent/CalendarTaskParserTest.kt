package com.example.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarTaskParserTest {

    private val now = ZonedDateTime.of(
        2026,
        8,
        22,
        10,
        0,
        0,
        0,
        ZoneId.of("Australia/Sydney"),
    )

    @Test
    fun parsesTomorrowAfternoonReminder() {
        val task = CalendarTaskParser.parse(
            "明天下午3点提醒我提交作业，提前30分钟通知",
            now,
        )

        val expectedStart = ZonedDateTime.of(
            2026,
            8,
            23,
            15,
            0,
            0,
            0,
            now.zone,
        )
        assertEquals("提交作业", task.title)
        assertEquals(expectedStart.toInstant().toEpochMilli(), task.startMillis)
        assertEquals(30, task.reminderMinutes)
    }

    @Test
    fun parsesHalfPastAndHourlyReminder() {
        val task = CalendarTaskParser.parse(
            "后天上午9点半提醒我参加组会，提前2小时通知",
            now,
        )

        val expectedStart = ZonedDateTime.of(
            2026,
            8,
            24,
            9,
            30,
            0,
            0,
            now.zone,
        )
        assertEquals("参加组会", task.title)
        assertEquals(expectedStart.toInstant().toEpochMilli(), task.startMillis)
        assertEquals(120, task.reminderMinutes)
    }

    @Test
    fun parsesRelativeReminderForFastDemo() {
        val task = CalendarTaskParser.parse(
            "2分钟后提醒我测试通知",
            now,
        )

        assertEquals("测试", task.title)
        assertEquals(now.plusMinutes(2).toInstant().toEpochMilli(), task.startMillis)
        assertEquals(0, task.reminderMinutes)
    }

    @Test
    fun rejectsPromptWithoutDate() {
        assertThrows(IllegalArgumentException::class.java) {
            CalendarTaskParser.parse("下午3点提醒我提交作业", now)
        }
    }
}
