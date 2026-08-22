package com.example.agent

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import java.util.TimeZone

data class CreatedCalendarEvent(
    val eventId: Long,
    val calendarName: String,
    val startMillis: Long,
)

class CalendarRepository(context: Context) {

    private val resolver = context.contentResolver

    fun createReminder(task: CalendarTask): CreatedCalendarEvent {
        val calendar = findWritableCalendar()
            ?: throw IllegalStateException("没有可写日历，请先在模拟器登录 Google Calendar")

        val eventValues = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendar.id)
            put(CalendarContract.Events.TITLE, task.title)
            put(CalendarContract.Events.DESCRIPTION, "Created by WellphoneAgent")
            put(CalendarContract.Events.DTSTART, task.startMillis)
            put(CalendarContract.Events.DTEND, task.endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
            put(
                CalendarContract.Events.STATUS,
                CalendarContract.Events.STATUS_CONFIRMED,
            )
            put(
                CalendarContract.Events.AVAILABILITY,
                CalendarContract.Events.AVAILABILITY_BUSY,
            )
        }

        val eventUri = resolver.insert(CalendarContract.Events.CONTENT_URI, eventValues)
            ?: throw IllegalStateException("Calendar Provider 拒绝创建事件")
        val eventId = ContentUris.parseId(eventUri)

        try {
            val reminderValues = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, task.reminderMinutes)
                put(
                    CalendarContract.Reminders.METHOD,
                    CalendarContract.Reminders.METHOD_ALERT,
                )
            }
            check(resolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues) != null) {
                "无法创建事件提醒"
            }
            check(eventExists(eventId)) {
                "事件写入后无法验证"
            }
            resolver.notifyChange(eventUri, null)
            resolver.notifyChange(CalendarContract.Events.CONTENT_URI, null)
            resolver.notifyChange(CalendarContract.Reminders.CONTENT_URI, null)
        } catch (error: Exception) {
            resolver.delete(eventUri, null, null)
            throw error
        }

        requestAndAwaitSync(calendar, eventId)
        return CreatedCalendarEvent(
            eventId = eventId,
            calendarName = calendar.name,
            startMillis = task.startMillis,
        )
    }

    private fun findWritableCalendar(): WritableCalendar? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
        )
        val selection =
            "${CalendarContract.Calendars.VISIBLE}=1 AND " +
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?"
        val selectionArgs = arrayOf(
            CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString(),
        )
        val sortOrder =
            "${CalendarContract.Calendars.IS_PRIMARY} DESC, " +
                "${CalendarContract.Calendars._ID} ASC"

        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val displayName = cursor.getString(1)
                val accountName = cursor.getString(2)
                return WritableCalendar(
                    id = cursor.getLong(0),
                    name = displayName.ifBlank { accountName },
                    accountName = accountName,
                    accountType = cursor.getString(3),
                )
            }
        }
        return null
    }

    private fun eventExists(eventId: Long): Boolean {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        resolver.query(
            uri,
            arrayOf(CalendarContract.Events._ID),
            null,
            null,
            null,
        )?.use { cursor ->
            return cursor.moveToFirst()
        }
        return false
    }

    private fun requestAndAwaitSync(
        calendar: WritableCalendar,
        eventId: Long,
    ): Boolean {
        if (calendar.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL ||
            calendar.accountName.isBlank() ||
            calendar.accountType.isBlank()
        ) {
            return true
        }

        while (true) {
            val extras = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            ContentResolver.requestSync(
                Account(calendar.accountName, calendar.accountType),
                CalendarContract.AUTHORITY,
                extras,
            )

            repeat(SYNC_POLLS_BEFORE_RETRY) {
                if (isEventSynced(eventId)) {
                    return true
                }
                Thread.sleep(SYNC_POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun isEventSynced(eventId: Long): Boolean {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        resolver.query(
            uri,
            arrayOf(CalendarContract.Events.DIRTY),
            null,
            null,
            null,
        )?.use { cursor ->
            return cursor.moveToFirst() && cursor.getInt(0) == 0
        }
        return false
    }

    private data class WritableCalendar(
        val id: Long,
        val name: String,
        val accountName: String,
        val accountType: String,
    )

    companion object {
        private const val SYNC_POLL_INTERVAL_MILLIS = 250L
        private const val SYNC_POLLS_BEFORE_RETRY = 40
    }
}
