// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only
package io.github.hcw3643cyber.brushalarm.ui

import android.Manifest
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.hcw3643cyber.brushalarm.alarm.*
import io.github.hcw3643cyber.brushalarm.verification.BrushMotionAnalyzer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VerificationActivity : ComponentActivity() {
    private var hasCamera by mutableStateOf(false)
    private var progress by mutableFloatStateOf(0f)
    private var hint by mutableStateOf("正在恢复起床任务…")
    private var session by mutableStateOf<AlarmSession?>(null)
    private var queueSize by mutableIntStateOf(0)
    private var quietSeconds by mutableLongStateOf(0)
    private var commandError by mutableStateOf<String?>(null)
    private var cameraError by mutableStateOf(false)
    private var cameraRevision by mutableIntStateOf(0)
    private var quietSubmitting by mutableStateOf(false)
    private var verificationSubmitting = false
    private var verificationFinished = false
    private var seenRevision = -1L
    private var cameraGeneration = 0L
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var analyzer: BrushMotionAnalyzer? = null
    private var groundTruthLabel by mutableStateOf("未标记")
    private var groundTruth: Boolean? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
        if (!it) hint = "需要摄像头权限才能完成刷牙验证"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCamera) permission.launch(Manifest.permission.CAMERA)
        lifecycleScope.launch {
            AlarmSessionStore.states.filterNotNull().collect { bindState(it) }
        }
        lifecycleScope.launch {
            while (isActive) {
                quietSeconds = session?.quietRemaining(AlarmSessionStore.time(this@VerificationActivity))
                    ?.let { (it + 999) / 1_000 } ?: 0
                delay(250)
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF9BC5A5))) { VerificationScreen() }
        }
    }

    override fun onResume() {
        super.onResume()
        restore()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        restore()
    }

    private fun restore() {
        lifecycleScope.launch {
            try {
                val state = AlarmSessionCoordinator.reconcile(this@VerificationActivity, forceSchedule = true)
                bindState(state)
                if (state.current != null) AlarmSessionCoordinator.requestService(this@VerificationActivity)
            } catch (error: Exception) {
                AlarmSessionCoordinator.failure(this@VerificationActivity, "verification_restore_failed", error)
                commandError = "恢复任务失败，请重新打开应用重试"
            }
        }
    }

    private fun bindState(state: SessionState) {
        val published = AlarmSessionStore.states.value
        if (state.revision < seenRevision || (published != null && state.revision < published.revision)) return
        seenRevision = state.revision
        val next = state.current
        queueSize = state.queue.size
        if (next?.token != session?.token) {
            stopCamera()
            progress = 0f
            groundTruth = null
            groundTruthLabel = "未标记"
            cameraError = false
            commandError = null
            verificationSubmitting = false
            hint = if (next == null) "本次起床任务已完成" else "请将牙刷和脸部放在画面中，开始刷牙"
        }
        session = next
        if (next != null) verificationFinished = false
        quietSeconds = next?.quietRemaining(AlarmSessionStore.time(this))?.let { (it + 999) / 1_000 } ?: 0
        if (next == null && !verificationFinished) {
            // No task is never interpreted as camera success. The durable core already removed it.
            verificationFinished = true
            window.decorView.postDelayed({
                if (verificationFinished && session == null) finishAndRemoveTask()
            }, 900)
        }
    }

    @Composable
    private fun VerificationScreen() {
        val current = session
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (hasCamera && current != null) {
                key(current.token, cameraRevision) { CameraPreview(Modifier.fillMaxSize(), current.token) }
            }
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("刷牙验证", color = Color.White, fontSize = 30.sp)
                    Text("视频仅在手机上实时分析，不会保存或上传", color = Color.White.copy(alpha = .75f))
                    if (queueSize > 1) Text("还有 ${queueSize - 1} 个起床任务等待验证", color = Color.White)
                }
                Card(colors = CardDefaults.cardColors(Color.Black.copy(alpha = .72f))) {
                    Column(Modifier.padding(20.dp)) {
                        Text(hint, color = Color.White, fontSize = 18.sp)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp))
                        Text("${(progress * 100).toInt()}%", color = Color.White)
                        commandError?.let { Text(it, color = Color(0xFFFFC6B8)) }
                        if (!hasCamera) Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("允许摄像头") }
                        if (cameraError && current != null) OutlinedButton(onClick = {
                            stopCamera(); cameraError = false; cameraRevision++; hint = "正在重新准备摄像头…"
                        }) { Text("重试摄像头") }
                        if (current?.roommate == true) {
                            OutlinedButton(onClick = { quiet(current.token) },
                                enabled = !current.quietUsed && !quietSubmitting,
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                                Text(when {
                                    quietSeconds > 0 -> "已安静，${quietSeconds} 秒后恢复提醒"
                                    current.quietUsed -> "本次静音额度已用尽"
                                    quietSubmitting -> "正在安排复响…"
                                    else -> "先安静 1 分钟（仅一次）"
                                })
                            }
                        }
                        TestVerificationControls(label = groundTruthLabel,
                            onBrushing = { markGroundTruth(true) }, onStopped = { markGroundTruth(false) })
                    }
                }
            }
        }
    }

    @Composable
    private fun CameraPreview(modifier: Modifier, token: String) {
        val context = LocalContext.current
        AndroidView(modifier = modifier, factory = {
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                startCamera(this, token)
            }
        })
    }

    private fun startCamera(view: PreviewView, token: String) {
        val generation = ++cameraGeneration
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!cameraMatches(token, generation)) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val useCase = ImageAnalysis.Builder().setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    ).build()
                ).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis = useCase
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, useCase)
                // Large model reads/initialization do not block UI or the ringing service's main thread.
                cameraExecutor.execute {
                    try {
                        val created = BrushMotionAnalyzer(this,
                            onProgress = { value, text -> runOnUiThread {
                                if (cameraMatches(token, generation)) { progress = value; hint = text }
                            } },
                            onVerified = { runOnUiThread {
                                if (cameraMatches(token, generation)) verified(token)
                            } })
                        runOnUiThread {
                            if (!cameraMatches(token, generation)) created.close() else {
                                analyzer = created
                                groundTruth?.let(created::markGroundTruth)
                                useCase.setAnalyzer(cameraExecutor, created)
                            }
                        }
                    } catch (error: Exception) {
                        runOnUiThread { cameraFailure(token, generation, error) }
                    }
                }
            } catch (error: Exception) { cameraFailure(token, generation, error) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun cameraMatches(token: String, generation: Long): Boolean =
        !isFinishing && !isDestroyed && !verificationFinished && session?.token == token && cameraGeneration == generation

    private fun cameraFailure(token: String, generation: Long, error: Exception) {
        if (!cameraMatches(token, generation)) return
        AlarmSessionCoordinator.failure(this, "camera_prepare_failed", error)
        cameraError = true
        hint = "摄像头或识别模型暂时无法使用，请重试。起床提醒会继续。"
    }

    private fun stopCamera() {
        cameraGeneration++
        analysis?.clearAnalyzer()
        analysis = null
        cameraProvider?.unbindAll()
        analyzer?.close()
        analyzer = null
    }

    private fun quiet(token: String) {
        if (quietSubmitting) return
        quietSubmitting = true
        lifecycleScope.launch {
            try {
                val state = AlarmSessionCoordinator.quiet(this@VerificationActivity, token)
                bindState(state)
                if (state.current != null) AlarmSessionCoordinator.requestService(this@VerificationActivity)
            } catch (error: Exception) {
                AlarmSessionCoordinator.failure(this@VerificationActivity, "quiet_request_failed", error)
                commandError = "暂时无法安排 1 分钟后的提醒，请重试"
            } finally { quietSubmitting = false }
        }
    }

    private fun verified(token: String) {
        if (verificationSubmitting || verificationFinished || session?.token != token) return
        verificationSubmitting = true
        lifecycleScope.launch {
            try {
                val state = AlarmSessionCoordinator.complete(this@VerificationActivity, token)
                if (state.completed.none { it.token == token }) {
                    verificationSubmitting = false
                    bindState(state)
                    return@launch
                }
                bindState(state)
                if (state.current != null) {
                    AlarmSessionCoordinator.requestService(this@VerificationActivity)
                } else hint = "验证通过，早上好！"
            } catch (error: Exception) {
                AlarmSessionCoordinator.failure(this@VerificationActivity, "verification_commit_failed", error)
                verificationSubmitting = false
                commandError = "保存验证结果失败，请重试摄像头"
                cameraError = true
            }
        }
    }

    private fun markGroundTruth(brushing: Boolean) {
        groundTruth = brushing
        analyzer?.markGroundTruth(brushing)
        groundTruthLabel = if (brushing) "正在刷牙" else "没有刷牙"
    }

    override fun onDestroy() {
        stopCamera()
        cameraExecutor.shutdown()
        AlarmDiagnosticLog.record(this, "verification_activity_destroyed", details = "token=${session?.token}")
        super.onDestroy()
    }

    companion object {
        private const val ACTION_VERIFY = "brushalarm.VERIFY"
        /** A cold sticky restart has no published snapshot yet; the UI binds the durable current task. */
        fun restoreIntent(context: Context): Intent =
            Intent(context, VerificationActivity::class.java).setAction(ACTION_VERIFY)
                .setData(Uri.parse("brushalarm://verify/restore"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun intent(context: Context, alarmId: Long, token: String): Intent =
            Intent(context, VerificationActivity::class.java).setAction(ACTION_VERIFY)
                .setData(Uri.parse("brushalarm://verify/$token"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(AlarmReceiver.EXTRA_ID, alarmId).putExtra(AlarmSessionScheduler.EXTRA_TOKEN, token)

        fun pendingIntentOptions(): Bundle? = if (Build.VERSION.SDK_INT >= 35) {
            ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
        } else null
    }
}
