package com.example.brushalarm.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import com.example.brushalarm.BrushAlarmApp
import com.example.brushalarm.BuildConfig
import com.example.brushalarm.alarm.AlarmReceiver
import com.example.brushalarm.alarm.AlarmService
import com.example.brushalarm.data.AlarmMode
import com.example.brushalarm.verification.BrushMotionAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VerificationActivity : ComponentActivity() {
    private var hasCamera by mutableStateOf(false)
    private var progress by mutableFloatStateOf(0f)
    private var hint by mutableStateOf("正在准备摄像头…")
    private var roommateMode by mutableStateOf(false)
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: BrushMotionAnalyzer? = null
    private var verificationFinished = false
    private var groundTruthLabel by mutableStateOf("未标记")
    private var groundTruth: Boolean? = null

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        hasCamera = it
        if (!it) hint = "需要摄像头权限才能完成刷牙验证"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        hasCamera = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCamera) permission.launch(Manifest.permission.CAMERA)
        val id = intent.getLongExtra(AlarmReceiver.EXTRA_ID, -1)
        lifecycleScope.launch {
            roommateMode = withContext(Dispatchers.IO) {
                (application as BrushAlarmApp).database.alarms().get(id)?.mode ==
                    AlarmMode.ROOMMATE
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF9BC5A5))) {
                VerificationScreen()
            }
        }
    }

    @Composable
    private fun VerificationScreen() {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (hasCamera) CameraPreview(Modifier.fillMaxSize())
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("刷牙验证", color = Color.White, fontSize = 30.sp)
                    Text(
                        "视频仅在手机上实时分析，不会保存或上传",
                        color = Color.White.copy(alpha = .75f)
                    )
                }
                Card(colors = CardDefaults.cardColors(Color.Black.copy(alpha = .72f))) {
                    Column(Modifier.padding(20.dp)) {
                        Text(hint, color = Color.White, fontSize = 18.sp)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
                        )
                        Text("${(progress * 100).toInt()}%", color = Color.White)
                        if (!hasCamera) {
                            Button(
                                onClick = { permission.launch(Manifest.permission.CAMERA) },
                                modifier = Modifier.padding(top = 12.dp)
                            ) { Text("允许摄像头") }
                        }
                        if (roommateMode) {
                            OutlinedButton(
                                onClick = { quiet() },
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                            ) { Text("先安静 1 分钟") }
                        }
                        if (BuildConfig.DEBUG) {
                            Text(
                                "测试标签：$groundTruthLabel",
                                color = Color.White.copy(alpha = .75f),
                                modifier = Modifier.padding(top = 12.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { markGroundTruth(true) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("标记开始刷牙") }
                                OutlinedButton(
                                    onClick = { markGroundTruth(false) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("标记已停止") }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CameraPreview(modifier: Modifier) {
        val context = LocalContext.current
        AndroidView(
            modifier = modifier,
            factory = {
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    startCamera(this)
                }
            }
        )
    }

    private fun startCamera(view: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(cameraReady@{
            if (isFinishing || isDestroyed) return@cameraReady
            cameraProvider = future.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = view.surfaceProvider
            }
            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            val analysis = analysisBuilder.build().also {
                    analyzer = BrushMotionAnalyzer(
                        context = this,
                        onProgress = { value, text ->
                            runOnUiThread { progress = value; hint = text }
                        },
                        onVerified = { runOnUiThread { verified() } }
                    ).also { created ->
                        groundTruth?.let(created::markGroundTruth)
                    }
                    it.setAnalyzer(cameraExecutor, analyzer!!)
                }
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun quiet() {
        startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_QUIET))
        hint = "已安静 1 分钟，请继续刷牙"
    }

    private fun markGroundTruth(brushing: Boolean) {
        groundTruth = brushing
        analyzer?.markGroundTruth(brushing)
        groundTruthLabel = if (brushing) "正在刷牙" else "没有刷牙"
    }

    private fun verified() {
        if (verificationFinished) return
        verificationFinished = true
        startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_VERIFIED))
        hint = "验证通过，早上好！"
        cameraProvider?.unbindAll()
        window.decorView.postDelayed({ finishAndRemoveTask() }, 900)
    }

    @Deprecated("Back is disabled while the alarm is active")
    override fun onBackPressed() {
        hint = "完成刷牙验证后才能关闭"
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        analyzer?.close()
        analyzer = null
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
