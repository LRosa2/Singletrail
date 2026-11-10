package io.github.lrosa2.singletrail.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BackupReminderTest {

    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_000 * day

    @Test
    fun `nothing to lose, no reminder`() {
        assertNull(BackupReminder.due(lastBackupAt = null, now = now, routes = 0, rides = 0))
    }

    @Test
    fun `routes but no rides and no backup yet, no reminder`() {
        // Routes alone can be imported again from their files; ride history can't.
        assertNull(BackupReminder.due(lastBackupAt = null, now = now, routes = 5, rides = 0))
    }

    @Test
    fun `rides and never backed up`() {
        assertEquals(BackupDue.Never, BackupReminder.due(lastBackupAt = null, now = now, routes = 1, rides = 1))
    }

    @Test
    fun `a recent backup needs no reminder`() {
        assertNull(BackupReminder.due(lastBackupAt = now - 30 * day, now = now, routes = 3, rides = 9))
    }

    @Test
    fun `an old backup is reported in days`() {
        assertEquals(BackupDue.DaysAgo(47), BackupReminder.due(lastBackupAt = now - 47 * day - 5_000, now = now, routes = 3, rides = 9))
    }
}
