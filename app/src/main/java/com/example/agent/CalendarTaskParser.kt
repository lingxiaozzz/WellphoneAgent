package com.example.agent

import java.time.LocalTime
import java.time.ZonedDateTime

data class CalendarTask(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val reminderMinutes: Int,
)

object CalendarTaskParser {

    private val timePattern = Regex(
        """(?:(上午|下午|晚上|中午)\s*)?(\d{1,2})(?:\s*点\s*(?:(半)|(\d{1,2})\s*分?)?|[:：](\d{1,2}))""",
    )
    private val relativeMinutesPattern = Regex("""(\d+)\s*分钟后""")
    private val reminderPattern = Regex("""提前\s*(\d+)\s*(分钟|分|小时)""")
    private val halfHourReminderPattern = Regex("""提前\s*半\s*小时""")

    fun parse(
        prompt: String,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): CalendarTask {
        val normalized = prompt.trim()
        require(normalized.isNotEmpty()) { "请输入提醒内容" }

        val relativeMinutesMatch = relativeMinutesPattern.find(normalized)
        val timeMatch = if (relativeMinutesMatch == null) {
            timePattern.find(normalized)
                ?: throw IllegalArgumentException(
                    "请指定时间，例如1分钟后、下午3点或15:30",
                )
        } else {
            null
        }

        val start = if (relativeMinutesMatch != null) {
            val minutesFromNow = relativeMinutesMatch.groupValues[1].toLong()
            require(minutesFromNow in 1..7 * 24 * 60) {
                "相对提醒时间必须在1分钟到7天以内"
            }
            now.plusMinutes(minutesFromNow)
        } else {
            val dayOffset = when {
                normalized.contains("后天") -> 2L
                normalized.contains("明天") -> 1L
                normalized.contains("今天") -> 0L
                else -> throw IllegalArgumentException("请使用今天、明天或后天指定日期")
            }
            checkNotNull(timeMatch)
            val period = timeMatch.groupValues[1]
            var hour = timeMatch.groupValues[2].toInt()
            val minute = when {
                timeMatch.groupValues[3].isNotEmpty() -> 30
                timeMatch.groupValues[4].isNotEmpty() -> timeMatch.groupValues[4].toInt()
                timeMatch.groupValues[5].isNotEmpty() -> timeMatch.groupValues[5].toInt()
                else -> 0
            }

            require(hour in 0..23 && minute in 0..59) { "时间格式不正确" }
            if (period == "下午" || period == "晚上") {
                if (hour < 12) hour += 12
            } else if (period == "中午" && hour < 11) {
                hour += 12
            } else if (period == "上午" && hour == 12) {
                hour = 0
            }

            val date = now.toLocalDate().plusDays(dayOffset)
            date.atTime(LocalTime.of(hour, minute)).atZone(now.zone)
        }

        val reminderMatch = reminderPattern.find(normalized)
        val reminderMinutes = when {
            halfHourReminderPattern.containsMatchIn(normalized) -> 30
            reminderMatch == null && relativeMinutesMatch != null -> 0
            reminderMatch == null -> 30
            reminderMatch.groupValues[2] == "小时" ->
                reminderMatch.groupValues[1].toInt() * 60
            else -> reminderMatch.groupValues[1].toInt()
        }
        require(reminderMinutes in 0..7 * 24 * 60) { "提醒时间必须在事件前7天以内" }

        require(start.isAfter(now)) { "提醒时间必须晚于当前时间" }

        var title = normalized
            .replace("今天", "")
            .replace("明天", "")
            .replace("后天", "")
            .replace(halfHourReminderPattern, "")
            .replace("提醒我", "")
            .replace("提醒", "")
            .replace("通知我", "")
            .replace("通知", "")
            .replace(Regex("""[，,。；;：:]+"""), " ")
            .trim()
        relativeMinutesMatch?.value?.let { title = title.replace(it, "") }
        timeMatch?.value?.let { title = title.replace(it, "") }
        reminderMatch?.value?.let { title = title.replace(it, "") }
        title = title.trim().ifEmpty { "日历提醒" }

        return CalendarTask(
            title = title,
            startMillis = start.toInstant().toEpochMilli(),
            endMillis = start.plusHours(1).toInstant().toEpochMilli(),
            reminderMinutes = reminderMinutes,
        )
    }
}
