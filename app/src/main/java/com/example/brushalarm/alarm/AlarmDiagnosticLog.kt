package com.example.brushalarm.alarm

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.time.Instant

/**
 * Small device-encrypted event trail for diagnosing OEM alarm suppression.
 * Contains no camera or model data.
 */
object AlarmDiagnosticLog {
    private const val DIRECTORY = "alarm_diagnostics"
    private const val FILE = "alarm-events.csv"
    private const val MAX_BYTES = 512 * 1024L

    @Synchronized
    fun record(
        context: Context,
        event: String,
        alarmId: Long = -1,
        details: String = ""
    ) {
        runCatching {
            val file = deviceFile(context)
            if (file.length() > MAX_BYTES) file.delete()
            val isNew = !file.exists()
            FileWriter(file, true).use { writer ->
                if (isNew) writer.appendLine("utc,event,alarm_id,details")
                writer.appendLine(
                    "${csv(Instant.now().toString())},${csv(event)},$alarmId,${csv(details)}"
                )
            }
        }
    }

    fun share(activity: Activity): Boolean {
        val source = deviceFile(activity)
        if (!source.exists()) return false
        val destinationDirectory = File(activity.filesDir, DIRECTORY).apply { mkdirs() }
        val destination = File(destinationDirectory, FILE)
        source.copyTo(destination, overwrite = true)
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.files",
            destination
        )
        activity.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "导出闹钟诊断日志"
            )
        )
        return true
    }

    private fun deviceFile(context: Context): File {
        val directory = File(
            context.createDeviceProtectedStorageContext().filesDir,
            DIRECTORY
        ).apply { mkdirs() }
        return File(directory, FILE)
    }

    private fun csv(value: String): String =
        "\"${value.replace("\"", "\"\"")}\""
}
