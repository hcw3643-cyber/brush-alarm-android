// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only
package io.github.hcw3643cyber.brushalarm.alarm

import org.junit.Assert.*
import org.junit.Test

class AlarmSessionCoreTest {
    private val now = SessionTime(7, 1_000L)
    private fun enqueue(state: SessionState = SessionState(), id: Long = 1, at: Long = 100,
                        token: String = "first", roommate: Boolean = true) =
        AlarmSessionCore.enqueue(state, AlarmSession(token, id, at, roommate, "Brush"), now)

    @Test fun duplicateOccurrenceDoesNotResetQuietOrQueue() {
        val quiet = AlarmSessionCore.quiet(enqueue(), "first", now)
        val duplicate = enqueue(quiet, token = "different")
        assertEquals(quiet, duplicate)
        assertEquals(61_000L, duplicate.current!!.quietUntilElapsed)
    }
    @Test fun onlyOneQuietAllowanceAndOldTokensAreRejected() {
        val initial = enqueue()
        assertEquals(initial, AlarmSessionCore.quiet(initial, "old", now))
        val quiet = AlarmSessionCore.quiet(initial, "first", now)
        assertTrue(quiet.current!!.quietUsed)
        assertEquals(quiet, AlarmSessionCore.quiet(quiet, "first", SessionTime(7, 59_000)))
        val expired = AlarmSessionCore.reconcile(quiet, SessionTime(7, 61_000))
        assertEquals(0L, expired.current!!.quietUntilElapsed)
        assertEquals(expired, AlarmSessionCore.quiet(expired, "first", SessionTime(7, 62_000)))
    }
    @Test fun rebootPreservesTaskAndAllowanceButEndsTemporaryQuiet() {
        val quiet = AlarmSessionCore.quiet(enqueue(), "first", now)
        val reboot = AlarmSessionCore.reconcile(quiet, SessionTime(8, 10))
        assertEquals("first", reboot.current!!.token)
        assertTrue(reboot.current!!.quietUsed)
        assertEquals(0L, reboot.current!!.quietUntilElapsed)
    }
    @Test fun completionAdvancesFifoAndRejectsLateTriggerAndAnalysis() {
        val two = enqueue(enqueue(), 2, 200, "second")
        assertEquals(two, AlarmSessionCore.complete(two, "second", now))
        val next = AlarmSessionCore.complete(two, "first", now)
        assertEquals("second", next.current!!.token)
        assertFalse(next.current!!.quietUsed)
        assertEquals(next, enqueue(next, token = "late"))
        assertEquals(next, AlarmSessionCore.complete(next, "first", now))
        assertNull(AlarmSessionCore.complete(next, "second", now).current)
    }
    @Test fun completedNewerOccurrenceDoesNotEraseAnOlderUnfulfilledOccurrence() {
        val finished = AlarmSessionCore.complete(enqueue(at = 200), "first", now)
        assertEquals(100L, enqueue(finished, at = 100, token = "older").current!!.occurrenceAt)
    }
    @Test fun noTaskCommandsCannotCreateTasks() {
        val empty = SessionState()
        assertEquals(empty, AlarmSessionCore.quiet(empty, "first", now))
        assertEquals(empty, AlarmSessionCore.complete(empty, "first", now))
    }
    @Test fun recoveryDeadlineDoesNotSlideOnRepeatedReconcile() {
        val initial = enqueue()
        val deadline = initial.current!!.recoveryAtElapsed
        assertEquals(deadline, AlarmSessionCore.reconcile(initial, SessionTime(7, deadline - 1)).current!!.recoveryAtElapsed)
        assertTrue(AlarmSessionCore.reconcile(initial, SessionTime(7, deadline)).current!!.recoveryAtElapsed > deadline)
    }
    @Test fun quietUsesMonotonicClockAndCompletionWinsOverRecovery() {
        val quiet = AlarmSessionCore.quiet(enqueue(), "first", now)
        assertEquals(1_000L, quiet.current!!.quietRemaining(SessionTime(7, 60_000)))
        val completed = AlarmSessionCore.complete(quiet, "first", SessionTime(7, 60_000))
        assertNull(AlarmSessionCore.reconcile(completed, SessionTime(7, 120_000)).current)
    }
    @Test fun outputChangesWithVolumeAndQuietTakesPriority() {
        val ringing = enqueue().current!!
        assertEquals(AlarmOutputKind.VIBRATION, AlarmOutputPolicy.choose(ringing, now, 0))
        assertEquals(AlarmOutputKind.SOUND, AlarmOutputPolicy.choose(ringing, now, 1))
        assertEquals(AlarmOutputKind.VIBRATION, AlarmOutputPolicy.choose(ringing, now, 0))
        val quiet = AlarmSessionCore.quiet(enqueue(), "first", now).current!!
        assertEquals(AlarmOutputKind.NONE, AlarmOutputPolicy.choose(quiet, now, 5))
        assertEquals(AlarmOutputKind.NONE, AlarmOutputPolicy.choose(null, now, 0))
    }

    @Test fun overdueUnfulfilledOccurrenceIsPreservedOnOpenOrReboot() {
        assertEquals(100L, NormalOccurrencePolicy.restoreAt(100, false, 900))
        assertEquals(900L, NormalOccurrencePolicy.restoreAt(100, true, 900))
        assertEquals(900L, NormalOccurrencePolicy.restoreAt(0, false, 900))
    }

    @Test fun failedWriteDoesNotPublishQuietAndCanRetry() {
        var succeeds = false
        val durable = DurableSessionState(enqueue()) { succeeds }
        try {
            durable.update { AlarmSessionCore.quiet(it, "first", now) }
            fail("write failure must not be accepted")
        } catch (_: IllegalStateException) { }
        assertFalse(durable.snapshot.current!!.quietUsed)
        succeeds = true
        assertTrue(durable.update { AlarmSessionCore.quiet(it, "first", now) }.current!!.quietUsed)
    }

    @Test fun timezoneChangeRecomputesFutureAlarmButPreservesOverdueUnfulfilledAlarm() {
        assertEquals(800L, NormalOccurrencePolicy.restoreAt(900, false, 800, now = 500, recomputeFuture = true))
        assertEquals(400L, NormalOccurrencePolicy.restoreAt(400, false, 800, now = 500, recomputeFuture = true))
        assertEquals(900L, NormalOccurrencePolicy.restoreAt(900, false, 800, now = 500, recomputeFuture = false))
        assertEquals(800L, NormalOccurrencePolicy.restoreAt(400, true, 800, now = 500, recomputeFuture = true))
    }

    @Test fun interruptedRingingRequestsRecoveryWithoutChangingTask() {
        val ringing = enqueue()
        val before = ringing.copy()
        assertEquals(now.elapsed + 2_000L, AlarmSessionCore.interruptionRecoveryAt(ringing, now))
        assertEquals(before, ringing)
        assertFalse(ringing.current!!.quietUsed)
    }

    @Test fun interruptedQuietKeepsOriginalDeadlineAndAllowance() {
        val quiet = AlarmSessionCore.quiet(enqueue(), "first", now)
        val later = SessionTime(7, 40_000L)
        assertEquals(61_000L, AlarmSessionCore.interruptionRecoveryAt(quiet, later))
        assertTrue(quiet.current!!.quietUsed)
        assertEquals(61_000L, quiet.current!!.quietUntilElapsed)
        assertEquals(61_000L, AlarmSessionCore.interruptionRecoveryAt(quiet, SessionTime(7, 60_999L)))
    }

    @Test fun interruptionAfterQuietExpirationUsesFastRecovery() {
        val quiet = AlarmSessionCore.quiet(enqueue(), "first", now)
        assertEquals(63_000L, AlarmSessionCore.interruptionRecoveryAt(quiet, SessionTime(7, 61_000L)))
        assertTrue(quiet.current!!.quietUsed)
    }

    @Test fun interruptionCannotReviveCompletedTask() {
        assertNull(AlarmSessionCore.interruptionRecoveryAt(SessionState(), now))
        val completed = AlarmSessionCore.complete(enqueue(), "first", now)
        assertNull(AlarmSessionCore.interruptionRecoveryAt(completed, now))
    }

    @Test fun shorterRecoveryCapsPersistedOldDeadlineWithoutSlidingForward() {
        val initial = enqueue()
        val old = initial.copy(queue = listOf(initial.current!!.copy(recoveryAtElapsed = 16_000L)))
        val capped = AlarmSessionCore.reconcile(old, now)
        assertEquals(3_000L, capped.current!!.recoveryAtElapsed)
        assertEquals(3_000L, AlarmSessionCore.reconcile(capped, SessionTime(7, 2_000L)).current!!.recoveryAtElapsed)
        assertEquals(old.current!!.token, capped.current!!.token)
    }

    @Test fun frequentRecoveryKeepsQuietDeadlineAndCompletionWins() {
        var state = AlarmSessionCore.quiet(enqueue(), "first", now)
        for (elapsed in 3_000L..59_000L step 2_000L) {
            val time = SessionTime(7, elapsed)
            state = AlarmSessionCore.reconcile(state, time)
            assertEquals("first", state.current!!.token)
            assertTrue(state.current!!.quietUsed)
            assertEquals(61_000L, state.current!!.quietUntilElapsed)
            assertEquals(AlarmOutputKind.NONE, AlarmOutputPolicy.choose(state.current, time, 5))
        }
        val completed = AlarmSessionCore.complete(state, "first", SessionTime(7, 60_999L))
        assertNull(AlarmSessionCore.reconcile(completed, SessionTime(7, 63_000L)).current)
    }

}
