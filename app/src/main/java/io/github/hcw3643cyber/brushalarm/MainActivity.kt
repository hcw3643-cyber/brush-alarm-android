// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only

package io.github.hcw3643cyber.brushalarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.github.hcw3643cyber.brushalarm.alarm.AlarmScheduler
import io.github.hcw3643cyber.brushalarm.alarm.AlarmReceiver
import io.github.hcw3643cyber.brushalarm.alarm.AlarmService
import io.github.hcw3643cyber.brushalarm.alarm.AlarmDiagnosticLog
import io.github.hcw3643cyber.brushalarm.alarm.AlarmSessionCoordinator
import io.github.hcw3643cyber.brushalarm.data.AlarmEntity
import io.github.hcw3643cyber.brushalarm.data.AlarmMode
import io.github.hcw3643cyber.brushalarm.ui.VerificationActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import java.time.Instant
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private val alarms = mutableStateListOf<AlarmEntity>()
    private var showTimeEditor by mutableStateOf(false)
    private var editingAlarm by mutableStateOf<AlarmEntity?>(null)
    private var exactAlarmAllowed by mutableStateOf(true)
    private var batteryUnrestricted by mutableStateOf(true)
    private var fullScreenAlarmAllowed by mutableStateOf(true)
    private var showPermissionGuide by mutableStateOf(false)
    private var permissionRevision by mutableIntStateOf(0)
    private val isVivo = Build.MANUFACTURER.equals("vivo", ignoreCase = true)
    private val runtimePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionRevision++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showPermissionGuide = !getSharedPreferences(ONBOARDING_FILE, MODE_PRIVATE)
            .getBoolean(ONBOARDING_SEEN, false)
        lifecycleScope.launch {
            (application as BrushAlarmApp).database.alarms().observeAll().collectLatest {
                alarms.clear(); alarms.addAll(it)
            }
        }
        setContent { BrushAlarmTheme { AlarmHome() } }
    }

    override fun onResume() {
        super.onResume()
        permissionRevision++
        batteryUnrestricted = Build.VERSION.SDK_INT < 23 ||
            getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(packageName)
        fullScreenAlarmAllowed = Build.VERSION.SDK_INT < 34 ||
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        AlarmDiagnosticLog.record(
            this,
            event = "app_resumed",
            details = "manufacturer=${Build.MANUFACTURER} app_version=${BuildConfig.VERSION_NAME} pid=${android.os.Process.myPid()} " +
                "battery_unrestricted=$batteryUnrestricted " +
                "full_screen=$fullScreenAlarmAllowed"
        )
        exactAlarmAllowed = Build.VERSION.SDK_INT < 31 ||
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        // Re-register on every foreground entry. This restores alarms after an
        // OEM process cleaner once the user opens the app again, and also keeps
        // best-effort alarms registered when exact access has not been granted.
        lifecycleScope.launch {
            try {
                val current = AlarmSessionCoordinator.reconcile(this@MainActivity, forceSchedule = true).current
                if (current != null) {
                    AlarmSessionCoordinator.requestService(this@MainActivity)
                    startActivity(VerificationActivity.intent(this@MainActivity, current.alarmId, current.token))
                    finish()
                    return@launch
                }
            } catch (error: Exception) {
                AlarmSessionCoordinator.failure(this@MainActivity, "app_restore_failed", error)
            }
            val enabled = withContext(Dispatchers.IO) {
                (application as BrushAlarmApp).database.alarms().enabled()
            }
            enabled.forEach { alarm ->
                runCatching {
                    val scheduled = withContext(Dispatchers.IO) { AlarmScheduler.restore(this@MainActivity, alarm) }
                    withContext(Dispatchers.IO) {
                        (application as BrushAlarmApp).database.alarms().updateNextTrigger(alarm.id, scheduled.triggerAt)
                    }
                }.onFailure { AlarmSessionCoordinator.failure(this@MainActivity, "main_normal_restore_failed", it) }
            }
        }
    }

    @Composable
    private fun AlarmHome() {
        if (showPermissionGuide) PermissionGuideDialog()
        if (showTimeEditor) {
            val now = java.time.LocalTime.now()
            WheelTimePickerDialog(
                initialHour = editingAlarm?.hour ?: now.hour,
                initialMinute = editingAlarm?.minute ?: now.minute,
                initialMode = editingAlarm?.mode ?: AlarmMode.CONTINUOUS,
                initialWeekdays = editingAlarm?.weekdays ?: 0b1111111,
                onDismiss = { showTimeEditor = false },
                onDelete = editingAlarm?.let { alarm ->
                    { delete(alarm); showTimeEditor = false }
                },
                onConfirm = { hour, minute, mode, weekdays ->
                    saveTime(editingAlarm, hour, minute, mode, weekdays)
                    showTimeEditor = false
                }
            )
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .navigationBarsPadding()
                        .padding(horizontal = 22.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = { openTimeEditor(null) },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("添加闹钟", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "完成刷牙验证，结束本次提醒",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 14.dp)
                    )
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("刷牙闹钟", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "让一天从认真刷牙开始",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        Surface(shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surface) {
                            IconButton(onClick = { showPermissionGuide = true }) {
                                Icon(Icons.Default.Settings, "权限与可靠性设置", Modifier.size(21.dp))
                            }
                        }
                    }
                }
                item { NextAlarmCard() }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("我的闹钟", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "已开启 ${alarms.count { it.enabled }} 个",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
                if (alarms.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                                Text("安排你的第一个早晨", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "添加一个时间，选择持续响铃或舍友模式。",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }
                }
                items(alarms, key = { it.id }) { alarm -> AlarmCard(alarm) }
            }
        }
    }

    @Composable
    private fun NextAlarmCard() {
        val next = alarms.filter { it.enabled && it.nextTriggerAt > 0 }.minByOrNull { it.nextTriggerAt }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.fillMaxWidth().padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Alarm, null,
                        Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "下一次提醒${next?.let { " · ${formatNextDay(it.nextTriggerAt)}" } ?: ""}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (next != null) {
                    Text(
                        "%02d:%02d".format(next.hour, next.minute),
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-3).sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "起床，开始新的一天",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
                            Text(
                                modeLabel(next.mode), fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                    if (!exactAlarmAllowed) {
                        TextButton(onClick = { showPermissionGuide = true }, contentPadding = PaddingValues(0.dp)) {
                            Text("开启闹钟权限，让提醒更准时", fontSize = 12.sp)
                        }
                    }
                } else {
                    Text(
                        if (alarms.any { it.enabled }) "正在准备提醒" else "给明天一个好开始",
                        fontSize = 25.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 10.dp)
                    )
                    Text(
                        if (alarms.any { it.enabled }) "提醒时间准备好后会显示在这里"
                        else if (alarms.isEmpty()) "从下方添加你的第一个闹钟"
                        else "开启一个闹钟，下次提醒会显示在这里",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    @Composable
    private fun PermissionGuideDialog() {
        // Read this state so returning from a system permission page refreshes
        // all check marks without keeping warnings on the alarm home screen.
        permissionRevision
        val runtimeReady =
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT < 33 ||
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED)
        AlertDialog(
            onDismissRequest = { finishOnboarding() },
            title = { Text("完成闹钟可靠性设置") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "这些权限只在首次启动集中引导。之后可点首页右上角设置重新打开。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PermissionStep(
                        "相机与通知",
                        "相机用于本地刷牙识别；通知用于显示正在响铃的前台闹钟。",
                        runtimeReady,
                        "授权"
                    ) { requestRuntimePermissions() }
                    PermissionStep(
                        "闹钟和提醒",
                        "允许系统在熄屏和待机时精确触发。",
                        exactAlarmAllowed,
                        "打开"
                    ) { requestExactAlarmIfNeeded() }
                    PermissionStep(
                        "后台不受限",
                        "避免省电策略冻结闹钟接收和响铃服务。",
                        batteryUnrestricted,
                        "允许"
                    ) { requestBatteryUnrestricted() }
                    PermissionStep(
                        "锁屏全屏显示",
                        "响铃后直接覆盖锁屏进入刷牙验证。",
                        fullScreenAlarmAllowed,
                        "打开"
                    ) { requestFullScreenAlarm() }
                    if (isVivo) {
                        PermissionStep(
                            "vivo 厂商权限",
                            "请在应用权限中同时开启“自启动、关联启动、后台弹出界面、锁屏显示”，" +
                                "并在电池设置中选择“允许后台高耗电”。关联启动关系到熄屏时能否" +
                                "由系统启动闹钟；测试版和正式版需要分别设置。",
                            null,
                            "应用设置"
                        ) { openAppSettings() }
                    }
                    PermissionStep(
                        "防止退出验证",
                        "请在系统安全设置中开启“屏幕固定/固定应用”。闹钟验证时系统会" +
                            "请求固定屏幕，完成刷牙后自动解除。",
                        null,
                        "安全设置"
                    ) { openSecuritySettings() }
                    Text(
                        "普通应用无法禁止系统“强行停止”。屏幕固定可阻止误触 Home 和" +
                            "最近任务，但用户仍可按系统方式解除。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TestHomeControls(this@MainActivity)
                }
            },
            dismissButton = {
                TextButton(onClick = { finishOnboarding() }) { Text("稍后") }
            },
            confirmButton = {
                Button(onClick = { finishOnboarding() }) { Text("完成") }
            }
        )
    }

    @Composable
    private fun PermissionStep(
        title: String,
        description: String,
        granted: Boolean?,
        actionLabel: String,
        onClick: () -> Unit
    ) {
        Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "$title${when (granted) {
                            true -> " ✓"
                            false -> "（未开启）"
                            null -> ""
                        }}",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        description,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (granted != true) {
                    TextButton(onClick = onClick) { Text(actionLabel) }
                }
            }
        }
    }

    @Composable
    private fun AlarmCard(alarm: AlarmEntity) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "%02d:%02d".format(alarm.hour, alarm.minute),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-1.5).sp,
                        color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).clickable { openTimeEditor(alarm) }
                    )
                    Switch(
                        checked = alarm.enabled,
                        onCheckedChange = { setEnabled(alarm, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${formatWeekdays(alarm.weekdays)} · ${modeLabel(alarm.mode)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { openTimeEditor(alarm) },
                        contentPadding = PaddingValues(start = 10.dp, end = 0.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text("编辑", fontSize = 12.sp)
                        Icon(Icons.Default.ChevronRight, null, Modifier.size(15.dp))
                    }
                }
            }
        }
    }

    private fun openTimeEditor(existing: AlarmEntity?) {
        editingAlarm = existing
        showTimeEditor = true
    }

    private fun saveTime(existing: AlarmEntity?, hour: Int, minute: Int, mode: AlarmMode, weekdays: Int) {
        lifecycleScope.launch {
            val dao = (application as BrushAlarmApp).database.alarms()
            existing?.let { AlarmScheduler.cancel(this@MainActivity, it.id) }
            val draft = existing?.copy(hour = hour, minute = minute, mode = mode, weekdays = weekdays)
                ?: AlarmEntity(hour = hour, minute = minute, mode = mode, weekdays = weekdays)
            val id = withContext(Dispatchers.IO) { dao.upsert(draft) }
            val saved = draft.copy(id = id)
            if (saved.enabled) scheduleAndPersist(saved)
            requestExactAlarmIfNeeded()
        }
    }

    private fun setEnabled(alarm: AlarmEntity, enabled: Boolean) {
        lifecycleScope.launch {
            val changed = alarm.copy(enabled = enabled)
            withContext(Dispatchers.IO) {
                (application as BrushAlarmApp).database.alarms().upsert(changed)
            }
            if (enabled) {
                scheduleAndPersist(changed)
                requestExactAlarmIfNeeded()
            } else {
                AlarmScheduler.cancel(this@MainActivity, alarm.id)
                withContext(Dispatchers.IO) {
                    (application as BrushAlarmApp).database.alarms()
                        .updateNextTrigger(alarm.id, 0)
                }
            }
        }
    }

    private suspend fun scheduleAndPersist(alarm: AlarmEntity) {
        val scheduled = withContext(Dispatchers.IO) { AlarmScheduler.schedule(this@MainActivity, alarm) }
        withContext(Dispatchers.IO) {
            (application as BrushAlarmApp).database.alarms()
                .updateNextTrigger(alarm.id, scheduled.triggerAt)
        }
    }

    private fun delete(alarm: AlarmEntity) {
        AlarmScheduler.cancel(this, alarm.id)
        lifecycleScope.launch(Dispatchers.IO) {
            (application as BrushAlarmApp).database.alarms().delete(alarm)
        }
    }

    private fun requestExactAlarmIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31 &&
            !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        ) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        runtimePermissions.launch(permissions.toTypedArray())
    }

    private fun requestBatteryUnrestricted() {
        if (Build.VERSION.SDK_INT >= 23) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }.onFailure {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun requestFullScreenAlarm() {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
    }

    private fun openSecuritySettings() {
        startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }

    private fun finishOnboarding() {
        getSharedPreferences(ONBOARDING_FILE, MODE_PRIVATE)
            .edit()
            .putBoolean(ONBOARDING_SEEN, true)
            .apply()
        showPermissionGuide = false
    }

    private fun formatNextDay(epochMillis: Long): String {
        val day = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = java.time.LocalDate.now()
        return when (day) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            else -> "周${listOf("一", "二", "三", "四", "五", "六", "日")[day.dayOfWeek.value - 1]}"
        }
    }

    private fun modeLabel(mode: AlarmMode) = if (mode == AlarmMode.CONTINUOUS) "持续响铃" else "舍友模式"

    private fun formatWeekdays(weekdays: Int): String = when (weekdays) {
        0b1111111 -> "每天"
        0b0011111 -> "周一至周五"
        0b1100000 -> "周六、周日"
        else -> listOf("一", "二", "三", "四", "五", "六", "日")
            .filterIndexed { index, _ -> weekdays and (1 shl index) != 0 }
            .joinToString("、") { "周$it" }
    }

    companion object {
        private const val ONBOARDING_FILE = "onboarding"
        private const val ONBOARDING_SEEN = "permission_guide_seen"
    }
}

@Composable
private fun BrushAlarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF27785C),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE3EEE7),
            onPrimaryContainer = Color(0xFF182C25),
            secondary = Color(0xFF63766D),
            background = Color(0xFFF5F7F5),
            onBackground = Color(0xFF182C25),
            surface = Color.White,
            onSurface = Color(0xFF182C25),
            surfaceVariant = Color(0xFFE3EEE7),
            onSurfaceVariant = Color(0xFF63766D),
            outlineVariant = Color(0xFFE2E9E4)
        ),
        content = content
    )
}

@Composable
private fun DayToggle(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .height(48.dp)
            .toggleable(value = selected, onValueChange = { onClick() }),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(32.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WheelTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    initialMode: AlarmMode,
    initialWeekdays: Int,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onConfirm: (Int, Int, AlarmMode, Int) -> Unit
) {
    var hour by remember(initialHour) { mutableIntStateOf(initialHour) }
    var minute by remember(initialMinute) { mutableIntStateOf(initialMinute) }
    var mode by remember(initialMode) { mutableStateOf(initialMode) }
    var weekdays by remember(initialWeekdays) { mutableIntStateOf(initialWeekdays) }
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (onDelete == null) "添加闹钟" else "编辑闹钟",
                    fontSize = 23.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "取消编辑") }
            }
            Text("上下滑动选择时间", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.fillMaxWidth().height(216.dp), contentAlignment = Alignment.Center) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {}
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize()
                ) {
                    TimeWheel(count = 24, initial = initialHour, suffix = "时", onValueChange = { hour = it })
                    Text(":", fontSize = 34.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 2.dp))
                    TimeWheel(count = 60, initial = initialMinute, suffix = "分", onValueChange = { minute = it })
                }
            }
            Text("提醒模式", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = mode == AlarmMode.CONTINUOUS,
                    onClick = { mode = AlarmMode.CONTINUOUS },
                    label = { Text("持续响铃") }
                )
                FilterChip(
                    selected = mode == AlarmMode.ROOMMATE,
                    onClick = { mode = AlarmMode.ROOMMATE },
                    label = { Text("舍友模式") }
                )
            }
            Text(
                if (mode == AlarmMode.CONTINUOUS) "完成刷牙验证后，闹钟才会停止"
                else "本次可安静 1 分钟，仅一次；之后继续提醒",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Text("重复日期", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, name ->
                    val bit = 1 shl index
                    DayToggle(
                        label = name, selected = weekdays and bit != 0,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val changed = weekdays xor bit
                            if (changed != 0) weekdays = changed
                        }
                    )
                }
            }
            Button(
                onClick = { onConfirm(hour, minute, mode, weekdays) },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
            ) { Text("保存闹钟", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            if (onDelete != null) {
                TextButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Icon(Icons.Default.Delete, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("删除闹钟") }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这个闹钟？") },
            text = { Text("删除后，这个时间将不再提醒。") },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete?.invoke() }) { Text("删除") }
            }
        )
    }
}

@Composable
private fun TimeWheel(
    count: Int,
    initial: Int,
    suffix: String,
    itemHeight: Dp = 54.dp,
    onValueChange: (Int) -> Unit
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }
    val selected by remember {
        derivedStateOf {
            val next = if (state.firstVisibleItemScrollOffset > itemHeightPx / 2f) 1 else 0
            (state.firstVisibleItemIndex + next).coerceIn(0, count - 1)
        }
    }
    LaunchedEffect(selected) { onValueChange(selected) }

    LazyColumn(
        state = state,
        flingBehavior = fling,
        contentPadding = PaddingValues(vertical = 81.dp),
        modifier = Modifier.width(104.dp).height(216.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(count) { value ->
            val distance = abs(value - selected)
            Row(
                modifier = Modifier
                    .height(itemHeight)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = when (distance) {
                            0 -> 1f
                            1 -> .48f
                            else -> .20f
                        }
                        scaleX = if (distance == 0) 1f else .88f
                        scaleY = scaleX
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    "%02d".format(value),
                    fontSize = if (distance == 0) 31.sp else 24.sp,
                    fontWeight = if (distance == 0) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.End
                )
                Text(
                    suffix,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}
