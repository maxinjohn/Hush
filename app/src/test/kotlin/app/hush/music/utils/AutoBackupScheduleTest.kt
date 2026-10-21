package app.hush.music.utils

import app.hush.music.constants.AutoBackupFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A scheduled backup that the OS never ran is only recoverable at the next app open, so the
 * "was it missed" answer has to be exact: too eager and every launch writes a backup, too shy and
 * the skipped slot stays skipped. These tests pin both edges of that boundary.
 */
class AutoBackupScheduleTest {
    private val zone: ZoneId = ZoneId.of("UTC")

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
    ): LocalDateTime = LocalDateTime.of(year, month, day, hour, minute)

    private fun millis(value: LocalDateTime): Long = value.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a daily schedule is last due today once its time has passed`() {
        val now = at(2026, 10, 10, 9, 30)

        assertEquals(
            at(2026, 10, 10, 2),
            AutoBackupHelper.lastScheduledDueTime(AutoBackupFrequency.DAILY, hour = 2, minute = 0, dayOfWeek = 1, now = now),
        )
    }

    @Test
    fun `a daily schedule not yet reached today was last due yesterday`() {
        val now = at(2026, 10, 10, 1, 30)

        assertEquals(
            at(2026, 10, 9, 2),
            AutoBackupHelper.lastScheduledDueTime(AutoBackupFrequency.DAILY, hour = 2, minute = 0, dayOfWeek = 1, now = now),
        )
    }

    @Test
    fun `a weekly schedule is last due on its own weekday`() {
        // 2026-10-10 is a Saturday (dayOfWeek 6); the schedule is Mondays (1) at 02:00.
        val now = at(2026, 10, 10, 9, 0)

        assertEquals(
            at(2026, 10, 5, 2),
            AutoBackupHelper.lastScheduledDueTime(AutoBackupFrequency.WEEKLY, hour = 2, minute = 0, dayOfWeek = 1, now = now),
        )
    }

    @Test
    fun `a weekly schedule whose day has not arrived yet was last due the week before`() {
        // Saturday 01:00, schedule Mondays 02:00: this week's Monday has passed, so it is due.
        // Now test the moment before a Saturday schedule: due last Saturday.
        val now = at(2026, 10, 10, 1, 0)

        assertEquals(
            at(2026, 10, 3, 2),
            AutoBackupHelper.lastScheduledDueTime(AutoBackupFrequency.WEEKLY, hour = 2, minute = 0, dayOfWeek = 6, now = now),
        )
    }

    @Test
    fun `an off schedule is never due`() {
        assertNull(
            AutoBackupHelper.lastScheduledDueTime(
                AutoBackupFrequency.OFF,
                hour = 2,
                minute = 0,
                dayOfWeek = 1,
                now = at(2026, 10, 10, 9, 0),
            ),
        )
    }

    @Test
    fun `a device that has never backed up is due`() {
        assertTrue(AutoBackupHelper.isScheduledBackupDue(null, dueAt = at(2026, 10, 10, 2), zone = zone))
    }

    @Test
    fun `a backup written after the due instant settles the slot`() {
        val dueAt = at(2026, 10, 10, 2)

        assertFalse(
            AutoBackupHelper.isScheduledBackupDue(millis(at(2026, 10, 10, 2, 1)), dueAt = dueAt, zone = zone),
        )
    }

    @Test
    fun `a backup older than the due instant leaves the slot missed`() {
        val dueAt = at(2026, 10, 10, 2)

        // Yesterday's backup does not satisfy today's slot - this is the whole case the
        // catch-up exists for.
        assertTrue(
            AutoBackupHelper.isScheduledBackupDue(millis(at(2026, 10, 9, 2, 0)), dueAt = dueAt, zone = zone),
        )
    }

    @Test
    fun `a backup written exactly at the due instant counts as done`() {
        val dueAt = at(2026, 10, 10, 2)

        assertFalse(
            AutoBackupHelper.isScheduledBackupDue(millis(at(2026, 10, 10, 2, 0)), dueAt = dueAt, zone = zone),
        )
    }

    @Test
    fun `the scheduled initial delay points at the next occurrence, not the one just passed`() {
        // The enqueue path must not re-target a time that has already gone by today.
        val now = at(2026, 10, 10, 9, 30)
        val delay =
            AutoBackupHelper.calculateInitialDelayMillisFrom(
                hour = 2,
                minute = 0,
                dayOfWeek = null,
                now = now,
            )

        assertEquals(java.time.Duration.between(now, at(2026, 10, 11, 2)).toMillis(), delay)
    }
}
