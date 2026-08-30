package com.example.medreminder

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.medreminder.databinding.ActivityMainBinding
import com.example.medreminder.databinding.ItemDrugCardBinding
import com.example.medreminder.databinding.ItemTimeRowBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 首页按“服药任务”展示今天待确认和之后的安排。 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private data class UpcomingDose(
        val dateStr: String,
        val schedule: DoseSchedule,
        val scheduleIndex: Int,
        val overdue: Boolean
    )

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Toast.makeText(this, "没有通知权限，提醒将无法显示", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        DrugStore.reconcilePastOccurrences(this)
        ReminderManager.scheduleAllEnabledAlarms(this)
        binding.btnHistory.setOnClickListener { openHistory() }
        binding.btnMissedSummary.setOnClickListener { openHistory() }
        binding.btnReminderStatus.setOnClickListener { openMissingReminderSetting() }
        binding.btnManageBottom.setOnClickListener {
            startActivity(Intent(this, ManageActivity::class.java))
        }
        checkAllPermissions()
    }

    override fun onResume() {
        super.onResume()
        DrugStore.reconcilePastOccurrences(this)
        renderReminderStatus()
        renderDrugList()
    }

    private fun openHistory() = startActivity(Intent(this, HistoryActivity::class.java))

    private fun renderDrugList() {
        renderMissedSummary()
        val drugs = DrugStore.getAllDrugs(this)
        val list = binding.llDrugList
        list.removeAllViews()
        val pending = drugs.filter { it.enabled && nextOccurrences(it, 1).isNotEmpty() }
            .sortedBy { drug ->
                nextOccurrences(drug, 1).firstOrNull()?.let(::occurrenceMillis) ?: Long.MAX_VALUE
            }

        binding.tvEmpty.visibility = if (pending.isEmpty()) View.VISIBLE else View.GONE
        pending.forEach { drug ->
            val card = ItemDrugCardBinding.inflate(LayoutInflater.from(this), list, false)
            bindDrugCard(card, drug)
            list.addView(card.root)
        }
    }

    private fun renderMissedSummary() {
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }
        val count = DrugStore.missedCountOn(this, DrugStore.dateString(yesterday))
        binding.btnMissedSummary.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (count > 0) {
            binding.btnMissedSummary.text = "昨天有${count}次服药未确认 · 查看历史"
            binding.btnMissedSummary.contentDescription = "昨天有${count}次服药未确认，查看历史记录"
        }
    }

    private fun bindDrugCard(card: ItemDrugCardBinding, drug: Drug) {
        card.tvDrugName.text = drug.name
        ViewCompat.setAccessibilityHeading(card.tvDrugName, true)
        val occurrences = nextOccurrences(drug, 3)
        card.tvRemaining.text = "待处理${occurrences.size}次"
        card.llTimes.removeAllViews()

        val today = DrugStore.todayString()
        occurrences.forEachIndexed { index, occurrence ->
            val schedule = occurrence.schedule
            val label = schedule.displayName(occurrence.scheduleIndex)
            val timesText = schedule.reminderTimes.sortedBy { it.hour * 60 + it.minute }
                .joinToString("、") { it.format() }
            val row = ItemTimeRowBinding.inflate(LayoutInflater.from(this), card.llTimes, false)
            row.tvDate.text = when {
                occurrence.dateStr == today && occurrence.overdue -> "今天·已超时"
                else -> formatDateLabel(occurrence.dateStr, today)
            }
            row.tvTime.text = "$label  $timesText"
            row.tvDate.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            row.tvTime.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            ViewCompat.setScreenReaderFocusable(row.root, true)
            row.root.contentDescription = buildString {
                append(row.tvDate.text)
                append("，")
                append(label)
                append("，提醒时间")
                append(schedule.reminderTimes.sortedBy { it.hour * 60 + it.minute }
                    .joinToString("，") { spokenTime(it) })
            }

            val actionable = occurrence.dateStr == today && index == 0
            if (!actionable) {
                row.btnTaken.visibility = View.GONE
                row.btnIgnore.visibility = View.GONE
                row.root.alpha = 0.7f
            } else {
                row.btnTaken.contentDescription = "已服用，$label"
                row.btnIgnore.contentDescription = "跳过，$label"
                row.btnTaken.setOnClickListener {
                    DrugStore.markTaken(this, drug.id, schedule.scheduleKey, occurrence.dateStr)
                    clearOccurrence(drug.id, schedule.scheduleKey, occurrence.dateStr)
                    renderDrugList()
                    val message = "已完成${drug.name}$label"
                    binding.root.post { binding.root.announceForAccessibility(message) }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
                row.btnIgnore.setOnClickListener {
                    DrugStore.markSkipped(this, drug.id, schedule.scheduleKey, occurrence.dateStr)
                    clearOccurrence(drug.id, schedule.scheduleKey, occurrence.dateStr)
                    renderDrugList()
                    val message = "已跳过${drug.name}$label"
                    binding.root.post { binding.root.announceForAccessibility(message) }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
            card.llTimes.addView(row.root)
        }
    }

    private fun clearOccurrence(drugId: Int, scheduleKey: Int, dateStr: String) {
        ReminderManager.cancelOccurrenceAlarms(this, drugId, scheduleKey, dateStr)
        ReminderManager.cancelOccurrenceNotifications(this, drugId, scheduleKey, dateStr)
    }

    private fun spokenTime(time: ReminderTime): String = when {
        time.minute == 0 -> "${time.hour}点"
        else -> "${time.hour}点${time.minute}分"
    }

    private fun renderReminderStatus() {
        val missing = mutableListOf<String>()
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            missing.add("通知未开启")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) missing.add("精确提醒未开启")
        }
        binding.btnReminderStatus.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        if (missing.isNotEmpty()) {
            binding.btnReminderStatus.text = missing.joinToString("·") + "，点击处理"
        }
    }

    private fun openMissingReminderSetting() {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
    }

    /** 今天已经超时但未确认的任务仍保留；昨天的任务由历史页承接。 */
    private fun nextOccurrences(drug: Drug, count: Int): List<UpcomingDose> {
        val result = mutableListOf<UpcomingDose>()
        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val day = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        for (offset in 0 until 90) {
            val current = (day.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, offset) }
            val date = DrugStore.dateString(current)
            if (!drug.isScheduledOn(date)) continue
            drug.schedules.withIndex()
                .sortedBy { (_, schedule) -> schedule.reminderTimes.minOf { it.hour * 60 + it.minute } }
                .forEach { indexed ->
                    val schedule = indexed.value
                    if (DrugStore.isCompletedOn(this, drug.id, schedule.scheduleKey, date)) return@forEach
                    val firstMinutes = schedule.reminderTimes.minOf { it.hour * 60 + it.minute }
                    result.add(UpcomingDose(date, schedule, indexed.index, offset == 0 && firstMinutes < nowMinutes))
                }
            if (result.size >= count) break
        }
        return result.take(count)
    }

    private fun occurrenceMillis(occurrence: UpcomingDose): Long {
        val first = occurrence.schedule.reminderTimes.minBy { it.hour * 60 + it.minute }
        return ReminderManager.calendarFor(occurrence.dateStr, first)?.timeInMillis ?: Long.MAX_VALUE
    }

    private fun formatDateLabel(dateStr: String, todayStr: String): String {
        if (dateStr == todayStr) return "今天"
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val today = Calendar.getInstance().apply { time = parser.parse(todayStr) ?: Date() }
        val date = Calendar.getInstance().apply { time = parser.parse(dateStr) ?: Date() }
        today.set(Calendar.HOUR_OF_DAY, 0)
        today.set(Calendar.MINUTE, 0)
        today.set(Calendar.SECOND, 0)
        today.set(Calendar.MILLISECOND, 0)
        date.set(Calendar.HOUR_OF_DAY, 0)
        date.set(Calendar.MINUTE, 0)
        date.set(Calendar.SECOND, 0)
        date.set(Calendar.MILLISECOND, 0)
        val diffDays = java.time.temporal.ChronoUnit.DAYS.between(
            java.time.Instant.ofEpochMilli(today.timeInMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
            java.time.Instant.ofEpochMilli(date.timeInMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        ).toInt()
        return when (diffDays) {
            1 -> "明天"
            2 -> "后天"
            else -> "${date.get(Calendar.MONTH) + 1}月${date.get(Calendar.DAY_OF_MONTH)}日"
        }
    }

    private fun checkAllPermissions() {
        checkNotificationPermission()
        checkExactAlarmPermission()
        checkBatteryOptimization()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!manager.canScheduleExactAlarms()) {
                Toast.makeText(this, "请允许精确闹钟权限，否则提醒可能不准时", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            }
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!manager.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "请将本应用加入电池优化白名单", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            }
        }
    }
}
