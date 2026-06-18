package com.example.medreminder

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.gridlayout.widget.GridLayout
import com.example.medreminder.databinding.ActivityMainBinding
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Toast.makeText(this, "通知权限已授予", Toast.LENGTH_SHORT).show()
            } else {
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

        setupListeners()
        checkNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    /* ===================== 监听器 ===================== */

    private fun setupListeners() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.swEnabled.setOnCheckedChangeListener { _, isChecked ->
            ReminderManager.saveConfig(
                this,
                ReminderManager.getHour(this),
                ReminderManager.getMinute(this),
                ReminderManager.getIntervalDays(this),
                ReminderManager.getRepeatMinutes(this),
                ReminderManager.getMedName(this),
                isChecked
            )
            if (isChecked) {
                if (!checkExactAlarmPermission()) {
                    Toast.makeText(this, "请允许精确闹钟权限，否则提醒可能不准时", Toast.LENGTH_LONG).show()
                }
                ReminderManager.scheduleDailyAlarm(this)
                Toast.makeText(this, "提醒已开启", Toast.LENGTH_SHORT).show()
            } else {
                ReminderManager.cancelAllAlarms(this)
                Toast.makeText(this, "提醒已关闭", Toast.LENGTH_SHORT).show()
            }
            updateStatusCard()
        }

        binding.btnMarkTaken.setOnClickListener {
            ReminderManager.markTakenToday(this)
            ReminderManager.cancelRepeatAlarm(this)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(ReminderManager.NOTIFICATION_ID)
            updateUI()
            Toast.makeText(this, "已标记今天吃药完成", Toast.LENGTH_SHORT).show()
        }

        binding.btnTest.setOnClickListener {
            if (!checkNotificationPermission()) return@setOnClickListener
            val intent = Intent(this, AlarmReceiver::class.java)
            sendBroadcast(intent)
            Toast.makeText(this, "已触发测试通知", Toast.LENGTH_SHORT).show()
        }
    }

    /* ===================== UI 更新 ===================== */

    private fun updateUI() {
        binding.swEnabled.isChecked = ReminderManager.isEnabled(this)
        updateStatusCard()
        updateCalendar()
    }

    private fun updateStatusCard() {
        if (ReminderManager.isTakenToday(this)) {
            binding.tvStatusEmoji.text = "✅"
            binding.tvStatusText.text = "今天已吃药"
            binding.tvStatusHint.text = "太棒了，继续保持！"
            binding.tvStatusText.setTextColor(0xFF4CAF50.toInt())
        } else {
            binding.tvStatusEmoji.text = "⭕"
            binding.tvStatusText.text = "今天还没吃药"
            binding.tvStatusHint.text = "收到通知后记得点「已吃药」哦"
            binding.tvStatusText.setTextColor(0xFF333333.toInt())
        }
    }

    /* ===================== 日历生成 ===================== */

    private fun updateCalendar() {
        val takenDates = ReminderManager.getAllTakenDates(this)
        val today = Calendar.getInstance()

        // 生成星期表头
        val weekHeader = binding.llWeekHeader
        weekHeader.removeAllViews()
        val weekDays = arrayOf("日", "一", "二", "三", "四", "五", "六")
        for (day in weekDays) {
            val tv = TextView(this).apply {
                text = day
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            weekHeader.addView(tv)
        }

        // 生成 14 天日期网格
        val grid = binding.gridCalendar
        grid.removeAllViews()

        val startCal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -13) // 从 13 天前开始
        }

        val todayStr = ReminderManager.dateString(today)

        for (i in 0 until 14) {
            val dateStr = ReminderManager.dateString(startCal)
            val dayOfMonth = startCal.get(Calendar.DAY_OF_MONTH)
            val isToday = dateStr == todayStr
            val isTaken = takenDates.contains(dateStr)

            val tv = TextView(this).apply {
                text = dayOfMonth.toString()
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 10, 0, 10)

                // 用 GridLayout.LayoutParams
                val params = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply {
                    width = 0
                    height = LinearLayout.LayoutParams.WRAP_CONTENT
                }
                layoutParams = params

                when {
                    isTaken -> {
                        setBackgroundResource(R.drawable.bg_calendar_taken)
                        setTextColor(Color.WHITE)
                        setTypeface(null, Typeface.BOLD)
                        // 无障碍：已吃
                        contentDescription = "${startCal.get(Calendar.MONTH) + 1}月${dayOfMonth}日，已吃药"
                    }
                    isToday -> {
                        setBackgroundResource(R.drawable.bg_calendar_today)
                        setTextColor(Color.parseColor("#333333"))
                        setTypeface(null, Typeface.BOLD)
                        contentDescription = "今天，${startCal.get(Calendar.MONTH) + 1}月${dayOfMonth}日，${if (isTaken) "已吃" else "未吃"}"
                    }
                    else -> {
                        setBackgroundResource(R.drawable.bg_calendar_normal)
                        setTextColor(Color.parseColor("#999999"))
                        contentDescription = "${startCal.get(Calendar.MONTH) + 1}月${dayOfMonth}日，未吃药"
                    }
                }
            }
            grid.addView(tv)
            startCal.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    /* ===================== 权限 ===================== */

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                false
            } else true
        } else true
    }

    private fun checkExactAlarmPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (alarmManager.canScheduleExactAlarms()) {
                true
            } else {
                Toast.makeText(this, "请允许精确闹钟权限，否则提醒可能不准时", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply { data = Uri.parse("package:$packageName") })
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:$packageName") })
                }
                false
            }
        } else true
    }
}