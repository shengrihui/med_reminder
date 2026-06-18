package com.example.medreminder

import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.medreminder.databinding.ActivitySettingsBinding

/**
 * 设置页
 *
 * 功能：
 * - 药品名称
 * - 检查时间（TimePickerDialog）
 * - 服用频率（可调节步进器：1-30天）
 * - 重复提醒间隔（可调节步进器：5-120分钟，步长5分钟）
 * - 保存设置
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private var selectedHour = ReminderManager.DEFAULT_HOUR
    private var selectedMinute = ReminderManager.DEFAULT_MINUTE
    private var intervalDays = ReminderManager.DEFAULT_INTERVAL_DAYS
    private var repeatMinutes = ReminderManager.DEFAULT_REPEAT_MINUTES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 标题栏返回按钮
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "设置"

        loadConfig()
        setupListeners()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadConfig() {
        selectedHour = ReminderManager.getHour(this)
        selectedMinute = ReminderManager.getMinute(this)
        intervalDays = ReminderManager.getIntervalDays(this)
        repeatMinutes = ReminderManager.getRepeatMinutes(this)

        binding.etMedName.setText(ReminderManager.getMedName(this))
        binding.btnPickTime.text = String.format("%02d:%02d", selectedHour, selectedMinute)
        updateIntervalDisplay()
        updateRepeatDisplay()
    }

    private fun setupListeners() {
        // 时间选择
        binding.btnPickTime.setOnClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                selectedHour = hour
                selectedMinute = minute
                binding.btnPickTime.text = String.format("%02d:%02d", hour, minute)
            }, selectedHour, selectedMinute, true).show()
        }

        // 服用频率 - 减
        binding.btnIntervalMinus.setOnClickListener {
            if (intervalDays > 1) {
                intervalDays--
                updateIntervalDisplay()
            }
        }

        // 服用频率 - 加
        binding.btnIntervalPlus.setOnClickListener {
            if (intervalDays < 30) {
                intervalDays++
                updateIntervalDisplay()
            }
        }

        // 重复提醒 - 减
        binding.btnRepeatMinus.setOnClickListener {
            if (repeatMinutes > 5) {
                repeatMinutes -= 5
                updateRepeatDisplay()
            }
        }

        // 重复提醒 - 加
        binding.btnRepeatPlus.setOnClickListener {
            if (repeatMinutes < 120) {
                repeatMinutes += 5
                updateRepeatDisplay()
            }
        }

        // 保存
        binding.btnSave.setOnClickListener {
            val medName = binding.etMedName.text.toString().trim()
            if (medName.isEmpty()) {
                Toast.makeText(this, "请输入药品名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            ReminderManager.saveConfig(
                this,
                selectedHour,
                selectedMinute,
                intervalDays,
                repeatMinutes,
                medName,
                ReminderManager.isEnabled(this) // 保持开关状态不变
            )

            // 如果提醒已开启，重新注册闹钟
            if (ReminderManager.isEnabled(this)) {
                ReminderManager.cancelAllAlarms(this)
                ReminderManager.scheduleDailyAlarm(this)
            }

            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun updateIntervalDisplay() {
        binding.tvIntervalValue.text = when (intervalDays) {
            1 -> "每天"
            2 -> "隔天"
            else -> "每${intervalDays}天"
        }
    }

    private fun updateRepeatDisplay() {
        binding.tvRepeatValue.text = when {
            repeatMinutes < 60 -> "${repeatMinutes}分钟"
            else -> "${repeatMinutes / 60}小时"
        }
    }
}