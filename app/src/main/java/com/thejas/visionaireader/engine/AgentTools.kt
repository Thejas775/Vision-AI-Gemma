package com.thejas.visionaireader.engine

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The 5 actions the Personal Agent can perform after Gemma decides what to do.
 * Each returns a friendly result string the UI can speak / display.
 *
 * Design rule per LiteRT-LM field eval: every tool exposed to Gemma takes ≤ 2 args.
 */
class AgentTools(private val context: Context) {

    /** Add an event to the user's primary calendar. Returns success message. */
    fun addCalendarEvent(title: String, startDateTimeIso: String): ToolResult {
        if (!hasCalendarPermission()) {
            return ToolResult(false, "Calendar permission not granted.")
        }
        val startMs = parseIsoToMillis(startDateTimeIso)
            ?: return ToolResult(false, "Couldn't read the date from the document.")
        val endMs = startMs + 60 * 60 * 1000  // 1-hour default

        val calId = primaryCalendarId() ?: return ToolResult(false, "No calendar found on this phone.")
        Log.d("VisionAgent", "Calendar add → calId=$calId title='$title' startMs=$startMs (${formatHuman(startMs)})")

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            Log.d("VisionAgent", "Calendar insert URI: $uri")
            if (uri != null) ToolResult(true, "Added \"$title\" to your calendar.")
            else ToolResult(false, "Couldn't add the event.")
        } catch (e: Exception) {
            Log.e("VisionAgent", "Calendar insert error", e)
            ToolResult(false, "Calendar error: ${e.message}")
        }
    }

    /** Set a bill reminder 3 days before the due date. */
    fun setBillReminder(title: String, dueDateIso: String): ToolResult {
        if (!hasCalendarPermission()) {
            return ToolResult(false, "Calendar permission not granted.")
        }
        val dueMs = parseIsoToMillis(dueDateIso)
            ?: return ToolResult(false, "Couldn't read the due date.")
        val reminderMs = dueMs - 3L * 24 * 60 * 60 * 1000
        val nowMs = System.currentTimeMillis()
        val whenMs = if (reminderMs > nowMs) reminderMs else dueMs

        val calId = primaryCalendarId() ?: return ToolResult(false, "No calendar found on this phone.")

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, whenMs)
            put(CalendarContract.Events.DTEND, whenMs + 30 * 60 * 1000)
            put(CalendarContract.Events.TITLE, "Reminder: $title")
            put(CalendarContract.Events.DESCRIPTION, "Bill due ${formatHuman(dueMs)}.")
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) ToolResult(true, "Reminder set for ${formatHuman(whenMs)}.")
            else ToolResult(false, "Couldn't set the reminder.")
        } catch (e: Exception) {
            ToolResult(false, "Calendar error: ${e.message}")
        }
    }

    /** Warn the user about something safety-critical (expired food, allergen, etc.) */
    fun warnUser(message: String): ToolResult = ToolResult(true, message)

    /** Save the scanned document to internal app storage for later recall. */
    fun saveDocument(text: String, kind: String): ToolResult {
        return try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeKind = kind.lowercase().replace(Regex("[^a-z]"), "_")
            val file = java.io.File(context.filesDir, "scans/${safeKind}_$ts.txt")
            file.parentFile?.mkdirs()
            file.writeText(text)
            ToolResult(true, "Saved to your library.")
        } catch (e: Exception) {
            ToolResult(false, "Couldn't save: ${e.message}")
        }
    }

    /** Read text aloud — handled by the screen's TextToSpeech instance. Tool call signal only. */
    fun readAloud(text: String): ToolResult = ToolResult(true, text)

    // ── helpers ────────────────────────────────────────────────────────

    private fun hasCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Pick a writable calendar Google Calendar app will actually show.
     * Priority: Google primary → any Google calendar → any writable visible calendar.
     * Logs every calendar found so the user can debug which one events land in.
     */
    private fun primaryCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        val cursor = try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection, null, null, null
            )
        } catch (e: SecurityException) {
            Log.e("VisionAgent", "Calendar query SecurityException", e)
            return null
        }

        var googlePrimary: Long? = null
        var anyGoogle: Long? = null
        var anyWritable: Long? = null

        cursor?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val isPrimary = c.getInt(1) == 1
                val visible = c.getInt(2) == 1
                val accountName = c.getString(3) ?: ""
                val accountType = c.getString(4) ?: ""
                val displayName = c.getString(5) ?: ""
                val accessLevel = c.getInt(6)
                // Need >= CAL_ACCESS_CONTRIBUTOR (500) to insert events
                val writable = accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                val isGoogle = accountType == "com.google"

                Log.d("VisionAgent", "Calendar id=$id primary=$isPrimary visible=$visible " +
                    "writable=$writable account='$accountName' type='$accountType' " +
                    "name='$displayName' access=$accessLevel")

                if (!writable || !visible) continue
                when {
                    isGoogle && isPrimary && googlePrimary == null -> googlePrimary = id
                    isGoogle && anyGoogle == null -> anyGoogle = id
                    anyWritable == null -> anyWritable = id
                }
            }
        }

        val picked = googlePrimary ?: anyGoogle ?: anyWritable
        Log.d("VisionAgent", "Picked calId=$picked (googlePrimary=$googlePrimary anyGoogle=$anyGoogle anyWritable=$anyWritable)")
        return picked
    }

    private fun parseIsoToMillis(iso: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd"
        )
        for (p in patterns) {
            try {
                val sdf = SimpleDateFormat(p, Locale.US)
                sdf.timeZone = TimeZone.getDefault()
                return sdf.parse(iso)?.time
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    private fun formatHuman(ms: Long): String {
        return SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault()).format(Date(ms))
    }
}

data class ToolResult(val success: Boolean, val message: String)
