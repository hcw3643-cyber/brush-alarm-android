package com.example.brushalarm.verification

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.example.brushalarm.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale
import java.util.UUID

/**
 * Numeric inference diagnostics only. No camera frame, image, audio or video is
 * written. Ground-truth labels are set manually in debug UI.
 */
internal class InferenceLogWriter(context: Context) : AutoCloseable {
    private val startedAtNs = System.nanoTime()
    private val file = InferenceLogFiles.newSessionFile(context)
    private val writer = BufferedWriter(FileWriter(file))
    private val metadataPrefix = listOf(
        SCHEMA_VERSION,
        MODEL_ID,
        BuildConfig.VERSION_NAME,
        "${Build.MANUFACTURER} ${Build.MODEL}",
        Build.VERSION.SDK_INT.toString()
    ).joinToString(",") { csvCell(it) }

    @Volatile
    private var groundTruth = "unknown"

    init {
        writer.appendLine(
            "schema_version,model_id,app_version,device,android_sdk," +
                "elapsed_ms,event,ground_truth,window_span_ms,sample_fps," +
                "inference_ms,logit,confidence,decision,progress," +
                "high_threshold,low_threshold"
        )
        writer.flush()
    }

    @Synchronized
    fun markGroundTruth(brushing: Boolean) {
        groundTruth = if (brushing) "brushing" else "not_brushing"
        writeEvent("ground_truth_changed")
    }

    @Synchronized
    fun record(
        windowSpanMs: Double,
        sampleFps: Double,
        inferenceMs: Double,
        logit: Float,
        confidence: Float,
        decision: String,
        progress: Float
    ) {
        writer.appendLine(
            "$metadataPrefix," + String.format(
                Locale.US,
                "%d,inference,%s,%.3f,%.3f,%.3f,%.6f,%.6f,%s,%.6f,%.3f,%.3f",
                elapsedMs(), groundTruth, windowSpanMs, sampleFps, inferenceMs,
                logit, confidence, decision, progress,
                BrushDecisionFilter.HIGH_THRESHOLD,
                BrushDecisionFilter.LOW_THRESHOLD
            )
        )
        writer.flush()
    }

    @Synchronized
    private fun writeEvent(event: String) {
        writer.appendLine(
            "$metadataPrefix,${elapsedMs()},$event,$groundTruth,,,,,,,," +
                "${BrushDecisionFilter.HIGH_THRESHOLD}," +
                BrushDecisionFilter.LOW_THRESHOLD
        )
        writer.flush()
    }

    private fun elapsedMs(): Long = (System.nanoTime() - startedAtNs) / 1_000_000L

    @Synchronized
    override fun close() {
        writer.close()
    }

    private companion object {
        const val SCHEMA_VERSION = "1"
        const val MODEL_ID = "s3d-brush-2s-192-feedback-v1"

        fun csvCell(value: String): String =
            "\"${value.replace("\"", "\"\"")}\""
    }
}

internal object InferenceLogFiles {
    private const val DIRECTORY = "inference_logs"
    private const val MAX_LOG_FILES = 20

    fun newSessionFile(context: Context): File {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        directory.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_LOG_FILES - 1)
            ?.forEach { it.delete() }
        return File(directory, "brush-inference-${UUID.randomUUID()}.csv")
    }

    fun shareLatest(activity: Activity): Boolean {
        val latest = File(activity.filesDir, DIRECTORY)
            .listFiles()
            ?.filter { it.extension == "csv" }
            ?.maxByOrNull { it.lastModified() }
            ?: return false
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.files",
            latest
        )
        activity.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "导出最近一次推理日志"
            )
        )
        return true
    }
}
