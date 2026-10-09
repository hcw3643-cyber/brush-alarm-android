// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only
package io.github.hcw3643cyber.brushalarm.alarm

/** Wall time is deliberately absent from temporary quiet and recovery decisions. */
data class SessionTime(val boot: Int, val elapsed: Long)

data class AlarmSession(
    val token: String,
    val alarmId: Long,
    val occurrenceAt: Long,
    val roommate: Boolean,
    val label: String,
    val quietUsed: Boolean = false,
    val quietUntilElapsed: Long = 0,
    val boot: Int = -1,
    val recoveryAtElapsed: Long = 0,
    val rebootReminderElapsed: Long = 0
) {
    fun quietRemaining(time: SessionTime): Long =
        if (boot == time.boot) (quietUntilElapsed - time.elapsed).coerceAtLeast(0) else 0
    val occurrenceKey: String get() = "$alarmId/$occurrenceAt"
}

data class CompletedSession(val token: String, val occurrenceKey: String)

data class SessionState(
    val revision: Long = 0,
    val queue: List<AlarmSession> = emptyList(),
    // Exact identities, not a timestamp high-water mark: delivery may be out of order.
    val completed: List<CompletedSession> = emptyList(),
    // Receiving and registering tomorrow's normal alarm are not an atomic operation.
    val pendingNext: Map<Long, Long> = emptyMap(),
    val normalRepairBoot: Int = -1,
    val normalRepairAtElapsed: Long = 0
) {
    val current: AlarmSession? get() = queue.firstOrNull()
}

object AlarmSessionCore {
    const val QUIET_MS = 60_000L
    const val RECOVERY_MS = 2_000L // Best effort; Android may defer this in Doze.
    const val FAST_RECOVERY_MS = 2_000L
    private const val NORMAL_REPAIR_MS = 60_000L

    /** A task dismissal must neither spend nor shorten the user's existing quiet minute. */
    fun interruptionRecoveryAt(state: SessionState, time: SessionTime): Long? {
        val current = state.current ?: return null
        val quietRemaining = current.quietRemaining(time)
        return time.elapsed + if (quietRemaining > 0) quietRemaining else FAST_RECOVERY_MS
    }

    fun enqueue(state: SessionState, session: AlarmSession, time: SessionTime): SessionState {
        if (state.queue.any { it.occurrenceKey == session.occurrenceKey } ||
            state.completed.any { it.occurrenceKey == session.occurrenceKey }) return state
        return reconcile(state.copy(
            revision = state.revision + 1,
            queue = state.queue + session.copy(boot = time.boot),
            pendingNext = state.pendingNext + (session.alarmId to session.occurrenceAt)
        ), time)
    }

    fun reconcile(state: SessionState, time: SessionTime): SessionState {
        val repairAt = if (state.pendingNext.isEmpty()) 0 else
            if (state.normalRepairBoot != time.boot || state.normalRepairAtElapsed <= time.elapsed)
                time.elapsed + NORMAL_REPAIR_MS else state.normalRepairAtElapsed
        val repairBoot = if (state.pendingNext.isEmpty()) -1 else time.boot
        val normalized = if (repairAt == state.normalRepairAtElapsed && repairBoot == state.normalRepairBoot) state
            else state.copy(revision = state.revision + 1, normalRepairBoot = repairBoot, normalRepairAtElapsed = repairAt)
        val current = normalized.current ?: return normalized
        val changed = current.copy(
            boot = time.boot,
            quietUntilElapsed = if (current.boot == time.boot && current.quietUntilElapsed > time.elapsed)
                current.quietUntilElapsed else 0,
            recoveryAtElapsed = if (current.boot != time.boot || current.recoveryAtElapsed <= time.elapsed)
                time.elapsed + RECOVERY_MS else minOf(current.recoveryAtElapsed, time.elapsed + RECOVERY_MS),
            rebootReminderElapsed = if (current.boot != time.boot) time.elapsed + 1_000L
                else current.rebootReminderElapsed
        )
        if (changed == current) return normalized
        return normalized.copy(revision = normalized.revision + 1, queue = listOf(changed) + normalized.queue.drop(1))
    }

    fun quiet(state: SessionState, token: String, time: SessionTime): SessionState {
        val current = state.current ?: return state
        if (current.token != token || !current.roommate || current.quietUsed) return state
        val quiet = current.copy(quietUsed = true, boot = time.boot,
            quietUntilElapsed = time.elapsed + QUIET_MS)
        return state.copy(revision = state.revision + 1, queue = listOf(quiet) + state.queue.drop(1))
    }

    fun complete(state: SessionState, token: String, time: SessionTime): SessionState {
        val current = state.current ?: return state
        if (current.token != token) return state
        return reconcile(state.copy(
            revision = state.revision + 1,
            queue = state.queue.drop(1),
            completed = state.completed + CompletedSession(current.token, current.occurrenceKey)
        ), time)
    }
}

enum class AlarmOutputKind { NONE, SOUND, VIBRATION }
object AlarmOutputPolicy {
    fun choose(session: AlarmSession?, time: SessionTime, alarmVolume: Int): AlarmOutputKind = when {
        session == null || session.quietRemaining(time) > 0 -> AlarmOutputKind.NONE
        alarmVolume == 0 -> AlarmOutputKind.VIBRATION
        else -> AlarmOutputKind.SOUND
    }
}

object NormalOccurrencePolicy {
    /** Recovery preserves an overdue occurrence until it has actually entered the durable queue. */
    fun restoreAt(storedAt: Long, accepted: Boolean, nextCalendarAt: Long,
                  now: Long = 0, recomputeFuture: Boolean = false): Long =
        if (storedAt > 0 && !accepted && (!recomputeFuture || storedAt <= now)) storedAt else nextCalendarAt
}

/** Publishes only successful writes, including when a preferences implementation caches a failed write. */
class DurableSessionState(initial: SessionState, private val write: (SessionState) -> Boolean) {
    var snapshot = initial
        private set

    fun update(transform: (SessionState) -> SessionState): SessionState {
        val next = transform(snapshot)
        if (next == snapshot) return snapshot
        check(write(next)) { "Unable to persist alarm session" }
        snapshot = next
        return next
    }
}
