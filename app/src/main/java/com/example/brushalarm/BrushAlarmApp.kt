package com.example.brushalarm

import android.app.Application
import com.example.brushalarm.data.AlarmDatabase

class BrushAlarmApp : Application() {
    val database by lazy { AlarmDatabase.create(this) }
}
