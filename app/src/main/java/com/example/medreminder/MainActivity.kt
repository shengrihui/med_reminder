package com.example.medreminder

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.medreminder.databinding.MainActivityBinding

/**
 * 主界面
 *
 * 功能：
 * - 设置药品名、检查时间、频率、重复间隔
 * - 保存配置并注册闹钟
 * - 立即测试通知
 * - 手动标记今天已吃药
 * - 申请通知权限、精确闹钟权限
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: MainActivityBinding

    /** 当前选中的时间 */
    private var selectedHour = ReminderManager.DEFAULT_HOUR
    private var selectedMinute = ReminderManager.DEFAULT_MINUTE

    /** 通知权限申请回调 */
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
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 初始化界面
        loadConfigToUI()
        setupListeners()

        // 首次启动申请通知权限
        checkNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        // 每次回到界面刷新今日状态
        updateTodayStatus()
    }

    /** 把保存的配置加载到界面 */
    private fun loadConfigToUI() {
        selectedHour = ReminderManager.getHour(this)
        selectedMinute = ReminderManager.getMinute(this)
        binding.etMedName.setText(ReminderManager.getMedName(this))
        binding.btnPickTime.text = String.format("%02d:%02d", selectedHour, selectedMinute)

        // 频率
        when (ReminderManager.getIntervalDays(this)) {
            1 -> binding.rbEveryDay.isChecked = true
            2 -> binding.rbEvery2Day.isChecked = true
            3 -> binding.rbEvery3Day.isChecked = true
            else -> binding.rbEveryDay.isChecked = true
        }

        // 重复间隔
        when (ReminderManager.getRepeatMinutes(this)) {
            10 -> binding.rbRepeat10.isChecked = true
            30 -> binding.rbRepeat30.isChecked = true
            60 -> binding.rbRepeat60.isChecked = true
            else -> binding.rbRepeat10.isChecked = true
        }

        // 开关
        binding.swEnabled.isChecked = ReminderManager.isEnabled(this)

        updateTodayStatus()
    }

    /** 设置所有控件的点击监听 */
    private fun setupListeners() {
        // 时间选择
        binding.btnPickTime.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    selectedHour = hourOfDay
                    selectedMinute = minute
                    binding.btnPickTime.text = String.format("%02d:%02d", hourOfDay, minute)
                },
                selectedHour,
                selectedMinute,
                true // 24小时制
            ).show()
        }

        // 保存设置
        binding.btnSave.setOnClickListener {
            saveConfig()
        }

        // 立即测试（5秒后发通知）
        binding.btnTest.setOnClickListener {
            testNotification()
        }

        // 手动标记今天已吃药
        binding.btnMarkTaken.setOnClickListener {
            ReminderManager.markTakenToday(this)
            // 取消重复提醒
            ReminderManager.cancelRepeatAlarm(this)
            // 取消通知（如果有）
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(ReminderManager.NOTIFICATION_ID)
            updateTodayStatus()
            Toast.makeText(this, "已标记今天吃药完成", Toast.LENGTH_SHORT).show()
        }
    }

    /** 保存配置并注册闹钟 */
    private fun saveConfig() {
        val medName = binding.etMedName.text.toString().trim()
        if (medName.isEmpty()) {
            Toast.makeText(this, "请输入药品名称", Toast.LENGTH_SHORT).show()
            return
        }

        val intervalDays = when (binding.rgInterval.checkedRadioButtonId) {
            R.id.rbEveryDay -> 1
            R.id.rbEvery2Day -> 2
            R.id.rbEvery3Day -> 3
            else -> 1
        }

        val repeatMinutes = when (binding.rgRepeat.checkedRadioButtonId) {
            R.id.rbRepeat10 -> 10
            R.id.rbRepeat30 -> 30
            R.id.rbRepeat60 -> 60
            else -> 10
        }

        val enabled = binding.swEnabled.isChecked

        // 保存配置
        ReminderManager.saveConfig(
            this,
            selectedHour,
            selectedMinute,
            intervalDays,
            repeatMinutes,
            medName,
            enabled
        )

        // 重新注册闹钟
        ReminderManager.cancelAllAlarms(this)
        if (enabled) {
            // 检查精确闹钟权限
            if (!checkExactAlarmPermission()) {
                // 没权限也会注册（内部会降级），但提示一下
                Toast.makeText(this, "已保存，但精确闹钟权限未授予，提醒可能不准时", Toast.LENGTH_LONG).show()
            }
            ReminderManager.scheduleDailyAlarm(this)
            Toast.makeText(this, "已保存并开启提醒", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "已保存（提醒未开启）", Toast.LENGTH_SHORT).show()
        }

        updateTodayStatus()
    }

    /** 立即测试通知（直接触发一次闹钟广播） */
    private fun testNotification() {
        // 先确保通知权限
        if (!checkNotificationPermission()) {
            return
        }

        // 直接发送广播触发 AlarmReceiver（会走完整的"检查+发通知"流程）
        val intent = Intent(this, AlarmReceiver::class.java)
        sendBroadcast(intent)
        Toast.makeText(this, "已触发测试通知", Toast.LENGTH_SHORT).show()
    }

    /** 更新今日状态显示 */
    private fun updateTodayStatus() {
        if (ReminderManager.isTakenToday(this)) {
            binding.tvStatus.text = "已吃 ✅"
            binding.tvStatus.setTextColor(0xFF4CAF50.toInt()) // 绿色
        } else {
            binding.tvStatus.text = "未吃 ⭕"
            binding.tvStatus.setTextColor(0xFFF44336.toInt()) // 红色
        }
    }

    /** 检查通知权限，没有就申请 */
    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                false
            } else {
                true
            }
        } else {
            true // Android 13 以下不需要运行时申请
        }
    }

    /**
     * 检查精确闹钟权限
     *
     * Android 12+ 需要用户在系统设置里授权"精确闹钟"。
     * Android 14+ 如果声明了 USE_EXACT_ALARM（普通权限），则自动授予，无需检查。
     * 这里统一检查，没权限就引导用户去设置。
     */
    private fun checkExactAlarmPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (alarmManager.canScheduleExactAlarms()) {
                true
            } else {
                // 引导用户去设置开启
                Toast.makeText(
                    this,
                    "请允许精确闹钟权限，否则提醒可能不准时",
                    Toast.LENGTH_LONG
                ).show()
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    // 某些设备没有这个界面，跳到应用详情
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
                false
            }
        } else {
            true
        }
    }
}
