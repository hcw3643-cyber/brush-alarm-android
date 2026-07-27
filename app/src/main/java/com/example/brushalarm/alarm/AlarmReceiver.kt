package com.example.brushalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_START)
            .putExtra(EXTRA_ID, intent.getLongExtra(EXTRA_ID, -1))
        ContextCompat.startForegroundService(context, service)
    }
    companion object { const val EXTRA_ID = "alarm_id" }
}
