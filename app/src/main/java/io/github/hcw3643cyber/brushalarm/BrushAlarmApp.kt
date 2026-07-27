package io.github.hcw3643cyber.brushalarm

import android.app.Application
import io.github.hcw3643cyber.brushalarm.data.AlarmDatabase

class BrushAlarmApp : Application() {
    val database by lazy { AlarmDatabase.create(this) }
}
