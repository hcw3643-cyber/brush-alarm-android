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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.github.hcw3643cyber.brushalarm.alarm.AlarmScheduler
import io.github.hcw3643cyber.brushalarm.alarm.AlarmReceiver
import io.github.hcw3643cyber.brushalarm.alarm.AlarmService
import io.github.hcw3643cyber.brushalarm.alarm.AlarmDiagnosticLog
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
import java.time.format.DateTimeFormatter

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
            details = "manufacturer=${Build.MANUFACTURER} " +
                "battery_unrestricted=$batteryUnrestricted " +
                "full_screen=$fullScreenAlarmAllowed"
        )
        val activeAlarmId = AlarmService.activeAlarmId(this)
        if (activeAlarmId >= 0) {
            AlarmDiagnosticLog.record(
                this,
                event = "main_forwarding_to_verification",
                alarmId = activeAlarmId
            )
            startActivity(VerificationActivity.intent(this, activeAlarmId))
            // The verification screen becomes the task entry while ringing.
            // Finishing Main prevents onResume/startActivity ping-pong.
            finish()
            return
        }
        exactAlarmAllowed = Build.VERSION.SDK_INT < 31 ||
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        // Re-register on every foreground entry. This restores alarms after an
        // OEM process cleaner once the user opens the app again, and also keeps
        // best-effort alarms registered when exact access has not been granted.
        lifecycleScope.launch {
            val enabled = withContext(Dispatchers.IO) {
                (application as BrushAlarmApp).database.alarms().enabled()
            }
            enabled.forEach { scheduleAndPersist(it) }
        }
    }

    @Composable
    private fun AlarmHome() {
        if (showPermissionGuide) {
            PermissionGuideDialog()
        }
        if (showTimeEditor) {
            val now = java.time.LocalTime.now()
            WheelTimePickerDialog(
                initialHour = editingAlarm?.hour ?: now.hour,
                initialMinute = editingAlarm?.minute ?: now.minute,
                onDismiss = { showTimeEditor = false },
                onConfirm = { hour, minute ->
                    saveTime(editingAlarm, hour, minute)
                    showTimeEditor = false
                }
            )
        }
        Scaffold(
            containerColor = Color(0xFFF4F0E6),
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    text = { Text("添加闹钟") },
                    icon = { Icon(Icons.Default.Add, null) },
                    onClick = { openTimeEditor(null) }
                )
            }
        ) { padding ->
            Column(Modifier.padding(padding).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("刷牙闹钟", fontSize = 32.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showPermissionGuide = true }) {
                        Icon(Icons.Default.Settings, "权限与可靠性设置")
                    }
                }
                Text(
                    "起床不是按掉闹钟，是完成刷牙。",
                    color = Color(0xFF58635F),
                    modifier = Modifier.padding(top = 4.dp)
                )
                TestHomeControls(this@MainActivity)
                if (alarms.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(Color.White)) {
                        Text(
                            "还没有闹钟。添加一个时间，并选择持续响铃或舍友模式。",
                            Modifier.padding(24.dp)
                        )
                    }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(alarms, key = { it.id }) { alarm -> AlarmCard(alarm) }
                    item { Spacer(Modifier.height(80.dp)) }
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
                            "请在应用权限中开启“自启动、后台弹出界面、锁屏显示”，" +
                                "并在电池设置中选择“允许后台高耗电”。",
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
            colors = CardDefaults.cardColors(Color.White),
            modifier = Modifier.clickable { openTimeEditor(alarm) }
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%02d:%02d".format(alarm.hour, alarm.minute), fontSize = 38.sp)
                    Spacer(Modifier.weight(1f))
                    Switch(alarm.enabled, onCheckedChange = { setEnabled(alarm, it) })
                    IconButton(onClick = { delete(alarm) }) {
                        Icon(Icons.Default.Delete, "删除")
                    }
                }
                Text(
                    if (alarm.mode == AlarmMode.CONTINUOUS) "持续响铃，刷牙后停止"
                    else "舍友模式：可静音，每分钟复响",
                    color = Color(0xFF58635F)
                )
                Text(
                    "点击卡片可修改时间",
                    color = Color(0xFF7B8581),
                    fontSize = 12.sp
                )
                if (alarm.enabled && alarm.nextTriggerAt > 0) {
                    Text(
                        "下次：${formatTriggerTime(alarm.nextTriggerAt)}" +
                            if (exactAlarmAllowed) "（系统精确闹钟）" else "（可能延迟）",
                        color = Color(0xFF66716D),
                        fontSize = 12.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("模式", modifier = Modifier.padding(end = 12.dp))
                    FilterChip(
                        selected = alarm.mode == AlarmMode.CONTINUOUS,
                        onClick = { updateMode(alarm, AlarmMode.CONTINUOUS) },
                        label = { Text("持续") }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = alarm.mode == AlarmMode.ROOMMATE,
                        onClick = { updateMode(alarm, AlarmMode.ROOMMATE) },
                        label = { Text("舍友") }
                    )
                }
                Text("重复", modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf("一", "二", "三", "四", "五", "六", "日")
                        .forEachIndexed { index, name ->
                            val bit = 1 shl index
                            DayToggle(
                                modifier = Modifier.weight(1f),
                                label = name,
                                selected = alarm.weekdays and bit != 0,
                                onClick = {
                                    val changed = alarm.weekdays xor bit
                                    if (changed != 0) updateWeekdays(alarm, changed)
                                }
                            )
                        }
                }
            }
        }
    }

    private fun openTimeEditor(existing: AlarmEntity?) {
        editingAlarm = existing
        showTimeEditor = true
    }

    private fun saveTime(existing: AlarmEntity?, hour: Int, minute: Int) {
        lifecycleScope.launch {
            val dao = (application as BrushAlarmApp).database.alarms()
            existing?.let { AlarmScheduler.cancel(this@MainActivity, it.id) }
            val draft = existing?.copy(hour = hour, minute = minute)
                ?: AlarmEntity(hour = hour, minute = minute)
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

    private fun updateMode(alarm: AlarmEntity, mode: AlarmMode) {
        saveAndReschedule(alarm.copy(mode = mode))
    }

    private fun updateWeekdays(alarm: AlarmEntity, weekdays: Int) {
        saveAndReschedule(alarm.copy(weekdays = weekdays))
    }

    private fun saveAndReschedule(alarm: AlarmEntity) {
        lifecycleScope.launch {
            AlarmScheduler.cancel(this@MainActivity, alarm.id)
            withContext(Dispatchers.IO) {
                (application as BrushAlarmApp).database.alarms().upsert(alarm)
            }
            if (alarm.enabled) {
                scheduleAndPersist(alarm)
                requestExactAlarmIfNeeded()
            }
        }
    }

    private suspend fun scheduleAndPersist(alarm: AlarmEntity) {
        val scheduled = AlarmScheduler.schedule(this, alarm)
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

    private fun formatTriggerTime(epochMillis: Long): String {
        return DateTimeFormatter.ofPattern("M月d日 E HH:mm")
            .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
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
            primary = Color(0xFF425F57),
            secondary = Color(0xFF749F82),
            background = Color(0xFFF4F0E6)
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
            .height(40.dp)
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

@Composable
private fun WheelTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var hour by remember(initialHour) { mutableIntStateOf(initialHour) }
    var minute by remember(initialMinute) { mutableIntStateOf(initialMinute) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置时间", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "上下滑动选择时间",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Box(
                    Modifier.fillMaxWidth().height(216.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = .10f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().height(54.dp)
                    ) {}
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        TimeWheel(
                            count = 24,
                            initial = initialHour,
                            suffix = "时",
                            onValueChange = { hour = it }
                        )
                        Text(
                            ":",
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                        TimeWheel(
                            count = 60,
                            initial = initialMinute,
                            suffix = "分",
                            onValueChange = { minute = it }
                        )
                    }
                }
                Text(
                    "%02d:%02d".format(hour, minute),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            Button(onClick = { onConfirm(hour, minute) }) { Text("保存") }
        }
    )
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
