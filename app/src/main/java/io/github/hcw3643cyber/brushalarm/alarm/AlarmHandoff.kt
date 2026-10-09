// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only
package io.github.hcw3643cyber.brushalarm.alarm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.util.UUID

/** Bounded CPU handoff: receiver return is not proof that a service has acquired its lock. */
object AlarmHandoff {
    const val EXTRA_ID = "alarm_handoff_id"
    private val locks = mutableMapOf<String, PowerManager.WakeLock>()

    @Synchronized
    fun acquire(context: Context): String? {
        val id = UUID.randomUUID().toString()
        return runCatching {
            val wake = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BrushAlarm:receiver_handoff")
            wake.acquire(20_000)
            locks[id] = wake
            Handler(Looper.getMainLooper()).postDelayed({ release(id) }, 20_000)
            id
        }.onFailure { AlarmSessionCoordinator.failure(context, "receiver_wake_failed", it) }.getOrNull()
    }

    @Synchronized
    fun release(id: String?) {
        if (id == null) return
        locks.remove(id)?.let { runCatching { if (it.isHeld) it.release() } }
    }
}
