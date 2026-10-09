// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only
package io.github.hcw3643cyber.brushalarm.alarm

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Sole session writer, single process. Accessed on IO under the coordinator's mutex. */
object AlarmSessionStore {
    private var durable: DurableSessionState? = null
    private val published = MutableStateFlow<SessionState?>(null)
    val states = published.asStateFlow()

    fun time(context: Context) = SessionTime(
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1),
        SystemClock.elapsedRealtime()
    )

    @Synchronized
    fun read(context: Context): SessionState = load(context).snapshot

    @Synchronized
    fun update(context: Context, change: (SessionState) -> SessionState): SessionState {
        val store = load(context)
        val before = store.snapshot
        val state = store.update(change)
        published.value = state
        if (state != before) AlarmDiagnosticLog.record(context, "session_state_committed", state.current?.alarmId ?: -1,
            "revision=${state.revision} token=${state.current?.token} queued=${state.queue.size} completed=${state.completed.size} " +
                "quiet_used=${state.current?.quietUsed} quiet_until=${state.current?.quietUntilElapsed} boot=${state.current?.boot}")
        return state
    }

    private fun load(context: Context): DurableSessionState {
        durable?.let { return it }
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("alarm_sessions_v1", Context.MODE_PRIVATE)
        val encoded = prefs.getString("snapshot", null)
        // Corrupt state is an error, never silently interpreted as successful completion.
        val initial = if (encoded != null) decode(encoded) else migrateLegacy(context)
        return DurableSessionState(initial) { next ->
            prefs.edit().putString("snapshot", encode(next)).commit()
        }.also {
            if (encoded == null) check(prefs.edit().putString("snapshot", encode(initial)).commit())
            durable = it
            published.value = initial
        }
    }

    private fun migrateLegacy(context: Context): SessionState {
        val old = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("active_alarm_state", Context.MODE_PRIVATE)
        val id = old.getLong("active_alarm_id", -1)
        if (id < 0) return SessionState()
        val alarm = DirectBootAlarmStore.get(context, id)
        val session = AlarmSession(UUID.randomUUID().toString(), id, -1,
            alarm?.mode == io.github.hcw3643cyber.brushalarm.data.AlarmMode.ROOMMATE,
            alarm?.label ?: "起床刷牙")
        // The old build did not persist a quiet allowance. Conservatively regard it as used.
        return AlarmSessionCore.enqueue(SessionState(), session.copy(quietUsed = true), time(context))
    }

    private fun encode(state: SessionState): String = JSONObject().apply {
        put("revision", state.revision)
        put("normalRepairBoot", state.normalRepairBoot)
        put("normalRepairAt", state.normalRepairAtElapsed)
        put("queue", JSONArray().apply { state.queue.forEach { s -> put(JSONObject().apply {
            put("token", s.token); put("alarm", s.alarmId); put("at", s.occurrenceAt)
            put("roommate", s.roommate); put("label", s.label); put("used", s.quietUsed)
            put("quiet", s.quietUntilElapsed); put("boot", s.boot); put("recover", s.recoveryAtElapsed)
            put("reboot", s.rebootReminderElapsed)
        }) } })
        put("completed", JSONArray().apply { state.completed.forEach { s -> put(JSONObject().apply {
            put("token", s.token); put("occurrence", s.occurrenceKey)
        }) } })
        put("pendingNext", JSONObject().apply { state.pendingNext.forEach { (id, at) -> put(id.toString(), at) } })
    }.toString()

    private fun decode(encoded: String): SessionState {
        val json = JSONObject(encoded)
        val queue = json.getJSONArray("queue")
        val completed = json.getJSONArray("completed")
        val pending = json.getJSONObject("pendingNext")
        return SessionState(json.getLong("revision"), (0 until queue.length()).map { index ->
            val s = queue.getJSONObject(index)
            AlarmSession(s.getString("token"), s.getLong("alarm"), s.getLong("at"),
                s.getBoolean("roommate"), s.getString("label"), s.getBoolean("used"),
                s.getLong("quiet"), s.getInt("boot"), s.getLong("recover"), s.optLong("reboot"))
        }, (0 until completed.length()).map { index ->
            val s = completed.getJSONObject(index)
            CompletedSession(s.getString("token"), s.getString("occurrence"))
        }, pending.keys().asSequence().associate { it.toLong() to pending.getLong(it) },
            json.optInt("normalRepairBoot", -1), json.optLong("normalRepairAt"))
    }
}
