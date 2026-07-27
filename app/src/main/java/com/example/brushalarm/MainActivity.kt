package com.example.brushalarm

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import com.example.brushalarm.alarm.AlarmScheduler
import com.example.brushalarm.data.AlarmEntity
import com.example.brushalarm.data.AlarmMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val alarms = mutableStateListOf<AlarmEntity>()
    private var showTimeEditor by mutableStateOf(false)
    private var editingAlarm by mutableStateOf<AlarmEntity?>(null)
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch {
            (application as BrushAlarmApp).database.alarms().observeAll().collectLatest {
                alarms.clear(); alarms.addAll(it)
            }
        }
        setContent { BrushAlarmTheme { AlarmHome() } }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 31 ||
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        ) {
            lifecycleScope.launch {
                val enabled = withContext(Dispatchers.IO) {
                    (application as BrushAlarmApp).database.alarms().enabled()
                }
                enabled.forEach { AlarmScheduler.schedule(this@MainActivity, it) }
            }
        }
    }

    @Composable
    private fun AlarmHome() {
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
                Text("刷牙闹钟", fontSize = 32.sp)
                Text(
                    "起床不是按掉闹钟，是完成刷牙。",
                    color = Color(0xFF58635F),
                    modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
                )
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
            if (saved.enabled) AlarmScheduler.schedule(this@MainActivity, saved)
            requestExactAlarmIfNeeded()
        }
    }

    private fun setEnabled(alarm: AlarmEntity, enabled: Boolean) {
        lifecycleScope.launch {
            val changed = alarm.copy(enabled = enabled)
            withContext(Dispatchers.IO) {
                (application as BrushAlarmApp).database.alarms().upsert(changed)
            }
            if (enabled) AlarmScheduler.schedule(this@MainActivity, changed)
            else AlarmScheduler.cancel(this@MainActivity, alarm.id)
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
            if (alarm.enabled) AlarmScheduler.schedule(this@MainActivity, alarm)
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
    Box(modifier, contentAlignment = Alignment.Center) {
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(36.dp)
                .toggleable(value = selected, onValueChange = { onClick() })
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
