package com.example.medreminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.medreminder.databinding.ActivityMainBinding
import com.example.medreminder.databinding.ItemDrugCardBinding
import com.example.medreminder.databinding.ItemTimeRowBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 首页
 *
 * - 只显示今天还没吃完的药（已吃/已忽略的时间点不显示）
 * - 每个时间点有"吃了""忽略"按钮
 * - 顶部右侧：历史 入口
 * - 底部：管理药品
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "没有通知权限，提醒将无法显示", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        binding.btnManageBottom.setOnClickListener {
            startActivity(Intent(this, ManageActivity::class.java))
        }

        checkAllPermissions()
    }

    override fun onResume() {
        super.onResume()
        renderDrugList()
    }

    /* ===================== 渲染列表 ===================== */

    private fun renderDrugList() {
        val drugs = DrugStore.getAllDrugs(this)
        val list = binding.llDrugList
        list.removeAllViews()

        // 过滤：只显示 enabled 且当前/未来还有提醒的药品
        val pendingDrugs = drugs.filter { drug ->
            if (!drug.enabled) return@filter false
            nextOccurrences(drug, 1).isNotEmpty()
        }

        if (pendingDrugs.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }
        binding.tvEmpty.visibility = View.GONE

        // 按第一个当前/未来提醒的时间排序
        val sortedPending = pendingDrugs.sortedBy { drug ->
            nextOccurrences(drug, 1).firstOrNull()?.let { (date, time) ->
                dateTimeCalendar(date, time)?.timeInMillis ?: Long.MAX_VALUE
            } ?: Long.MAX_VALUE
        }

        for (drug in sortedPending) {
            val card = ItemDrugCardBinding.inflate(LayoutInflater.from(this), list, false)
            bindDrugCard(card, drug)
            list.addView(card.root)
        }
    }

    private fun bindDrugCard(card: ItemDrugCardBinding, drug: Drug) {
        card.tvDrugName.text = drug.name

        val timesContainer = card.llTimes
        timesContainer.removeAllViews()

        val occurrences = nextOccurrences(drug, 3)
        card.tvRemaining.text = "未来${occurrences.size}次"

        val todayStr = DrugStore.dateString(Calendar.getInstance())

        for ((index, pair) in occurrences.withIndex()) {
            val (dateStr, time) = pair
            val row = ItemTimeRowBinding.inflate(LayoutInflater.from(this), timesContainer, false)

            row.tvDate.text = formatDateLabel(dateStr, todayStr)
            row.tvTime.text = time.format()
            row.root.contentDescription = "${drug.name} ${formatDateLabel(dateStr, todayStr)} ${time.format()}，未用药"

            // 只有今天的第一个未来时间点才显示操作按钮
            val isActionable = dateStr == todayStr && index == 0
            if (!isActionable) {
                row.btnTaken.visibility = View.GONE
                row.btnIgnore.visibility = View.GONE
                row.root.alpha = 0.7f
            } else {
                row.btnTaken.setOnClickListener {
                    DrugStore.markTaken(this, drug.id, time)
                    ReminderManager.cancelRepeatAlarm(this, drug.id, time)
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(ReminderManager.notificationId(drug.id, time))
                    renderDrugList()
                    Toast.makeText(this, "${drug.name} ${time.format()}：已标记用药", Toast.LENGTH_SHORT).show()
                }

                row.btnIgnore.setOnClickListener {
                    DrugStore.markIgnored(this, drug.id, time)
                    ReminderManager.cancelRepeatAlarm(this, drug.id, time)
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(ReminderManager.notificationId(drug.id, time))
                    renderDrugList()
                    Toast.makeText(this, "${drug.name} ${time.format()}：已忽略", Toast.LENGTH_SHORT).show()
                }
            }

            timesContainer.addView(row.root)
        }
    }

    /* ===================== 工具 ===================== */

    /** 取某药品接下来 count 个未完成的提醒（跨天），跳过已吃/已忽略的时间点 */
    private fun nextOccurrences(drug: Drug, count: Int): List<Pair<String, ReminderTime>> {
        val result = mutableListOf<Pair<String, ReminderTime>>()
        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val dayCal = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        for (offset in 0 until 90) {
            val currentDay = dayCal.clone() as Calendar
            currentDay.add(Calendar.DAY_OF_MONTH, offset)
            val dateStr = DrugStore.dateString(currentDay)
            if (!drug.isScheduledOn(dateStr)) continue
            for (time in drug.times.sortedBy { it.hour * 60 + it.minute }) {
                if (DrugStore.isCompletedOn(this, drug.id, time, dateStr)) continue
                // 今天已过去的时间点不再显示（避免标记最新时间点后，早些时候未操作的时间点冒出来）
                if (offset == 0 && (time.hour * 60 + time.minute) < nowMinutes) continue
                result.add(dateStr to time)
            }
            if (result.size >= count) break
        }
        return result.take(count)
    }

    private fun dateTimeCalendar(dateStr: String, time: ReminderTime): Calendar? {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            Calendar.getInstance().apply {
                setTime(parser.parse(dateStr) ?: Date())
                set(Calendar.HOUR_OF_DAY, time.hour)
                set(Calendar.MINUTE, time.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun formatDateLabel(dateStr: String, todayStr: String): String {
        if (dateStr == todayStr) return "今天"
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val today = Calendar.getInstance().apply {
            time = parser.parse(todayStr) ?: Date()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val date = Calendar.getInstance().apply {
            time = parser.parse(dateStr) ?: Date()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diffDays = ((date.timeInMillis - today.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
        return when (diffDays) {
            1 -> "明天"
            2 -> "后天"
            else -> "${date.get(Calendar.MONTH) + 1}月${date.get(Calendar.DAY_OF_MONTH)}日"
        }
    }

    /* ===================== 权限 ===================== */

    private fun checkAllPermissions() {
        checkNotificationPermission()
        checkExactAlarmPermission()
        checkBatteryOptimization()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /** 检查精确闹钟权限（Android 12+） */
    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                Toast.makeText(this, "请允许精确闹钟权限，否则锁屏/后台时提醒不会准时响", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            }
        }
    }

    /** 检查电池优化白名单（Android 6+） */
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "请将本应用加入电池优化白名单，否则后台提醒可能被延迟", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            }
        }
    }
}
