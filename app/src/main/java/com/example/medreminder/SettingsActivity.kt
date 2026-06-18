package com.example.medreminder

import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.medreminder.databinding.ActivitySettingsBinding
import com.example.medreminder.databinding.ItemTimeEditBinding
import java.util.Calendar

/**
 * 编辑药品页
 *
 * 两种模式：
 * - 编辑模式：drugId >= 0，加载已有药品配置，可保存/删除
 * - 新建模式：drugId = -1（EXTRA_IS_NEW），用默认配置初始化，保存时才真正创建药品；
 *   未保存直接返回则不会创建任何药品。
 *
 * - 支持多个提醒时间点的增删改（添加时默认当前时间）
 * - 时间点按时间排序显示（不改变存储索引）
 * - 服用频率、重复间隔（步进器）
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var drugId: Int = -1
    private var isNew: Boolean = false
    private var drug: Drug? = null

    private var times = mutableListOf<ReminderTime>()
    private var intervalDays = Drug.DEFAULT_INTERVAL_DAYS
    private var repeatMinutes = Drug.DEFAULT_REPEAT_MINUTES

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

        drugId = intent.getIntExtra(ReminderManager.EXTRA_DRUG_ID, -1)
        isNew = intent.getBooleanExtra(EXTRA_IS_NEW, false)

        // 设置 Toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (isNew) {
            // 新建模式：用默认配置初始化，不加载已有药品
            binding.toolbar.title = "添加药品"
            binding.btnDelete.visibility = View.GONE
            initDefaults()
        } else {
            // 编辑模式：加载已有药品
            if (drugId < 0) {
                Toast.makeText(this, "药品不存在", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            drug = DrugStore.getDrug(this, drugId)
            if (drug == null) {
                Toast.makeText(this, "药品不存在", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            binding.toolbar.title = "编辑药品"
            loadConfig()
        }

        setupListeners()
        renderTimes()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /* ===================== 加载 ===================== */

    /** 新建模式：用默认值初始化 */
    private fun initDefaults() {
        times = mutableListOf(ReminderTime.DEFAULT)
        intervalDays = Drug.DEFAULT_INTERVAL_DAYS
        repeatMinutes = Drug.DEFAULT_REPEAT_MINUTES
        binding.etMedName.setText("")
        binding.etMedName.hint = "例如：阿司匹林"
        updateIntervalDisplay()
        updateRepeatDisplay()
    }

    /** 编辑模式：加载已有药品配置 */
    private fun loadConfig() {
        val d = drug ?: return
        times = d.times.toMutableList()
        intervalDays = d.intervalDays
        repeatMinutes = d.repeatMinutes
        binding.etMedName.setText(d.name)
        updateIntervalDisplay()
        updateRepeatDisplay()
    }

    /* ===================== 监听 ===================== */

    private fun setupListeners() {
        binding.btnAddTime.setOnClickListener {
            // 默认用当前时间
            val cal = Calendar.getInstance()
            val newTime = ReminderTime(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            // 检查时间点是否重复（同 hour:minute）
            if (times.any { it.hour == newTime.hour && it.minute == newTime.minute }) {
                Toast.makeText(this, "已存在相同的时间点 ${newTime.format()}，请选其他时间", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            times.add(newTime)
            renderTimes()
        }

        binding.btnIntervalMinus.setOnClickListener {
            if (intervalDays > 1) { intervalDays--; updateIntervalDisplay() }
        }
        binding.btnIntervalPlus.setOnClickListener {
            if (intervalDays < 30) { intervalDays++; updateIntervalDisplay() }
        }

        binding.btnRepeatMinus.setOnClickListener {
            if (repeatMinutes > 5) { repeatMinutes -= 5; updateRepeatDisplay() }
        }
        binding.btnRepeatPlus.setOnClickListener {
            if (repeatMinutes < 120) { repeatMinutes += 5; updateRepeatDisplay() }
        }

        binding.btnSave.setOnClickListener {
            val name = binding.etMedName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "请输入药品名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (times.isEmpty()) {
                Toast.makeText(this, "至少需要一个提醒时间点", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isNew) {
                // 新建模式：保存时才创建
                if (DrugStore.isDrugNameDuplicate(this, name, excludeId = -1)) {
                    Toast.makeText(this, "已存在同名药品「$name」，请改名", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val created = DrugStore.createDrug(this, name, times.toList(), intervalDays, repeatMinutes)
                if (created == null) {
                    Toast.makeText(this, "创建失败，请重试", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!checkExactAlarmPermission()) {
                    // 即使权限不足也保留药品，只是闹钟可能不准
                    Toast.makeText(this, "药品已保存，但请授权精确闹钟权限", Toast.LENGTH_LONG).show()
                } else {
                    ReminderManager.scheduleAllDailyAlarms(this, created)
                }
                Toast.makeText(this, "已添加药品「$name」", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                // 编辑模式：更新已有药品
                val d = drug ?: return@setOnClickListener
                if (DrugStore.isDrugNameDuplicate(this, name, excludeId = d.id)) {
                    Toast.makeText(this, "已存在同名药品「$name」，请改名", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val updated = d.copy(
                    name = name,
                    times = times.toList(),
                    intervalDays = intervalDays,
                    repeatMinutes = repeatMinutes
                )
                DrugStore.saveDrug(this, updated)
                // 重新注册闹钟
                ReminderManager.cancelAllAlarms(this, d.id)
                if (updated.enabled) {
                    if (!checkExactAlarmPermission()) return@setOnClickListener
                    ReminderManager.scheduleAllDailyAlarms(this, updated)
                }
                Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        binding.btnDelete.setOnClickListener {
            if (isNew) return@setOnClickListener  // 新建模式无删除
            AlertDialog.Builder(this)
                .setTitle("删除药品")
                .setMessage("确定删除「${drug?.name}」吗？所有提醒和历史记录都会清除。")
                .setPositiveButton("删除") { _, _ ->
                    ReminderManager.cancelAllAlarms(this, drugId)
                    DrugStore.deleteDrug(this, drugId)
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /* ===================== 时间点列表（按时间排序显示） ===================== */

    private fun renderTimes() {
        val container = binding.llTimes
        container.removeAllViews()

        // 按时间排序索引，但不改变 times 列表的实际顺序
        val sortedIndices = times.indices.sortedBy { times[it].hour * 60 + times[it].minute }

        for (index in sortedIndices) {
            val time = times[index]
            val row = ItemTimeEditBinding.inflate(LayoutInflater.from(this), container, false)
            row.btnPickTime.text = time.format()
            row.btnPickTime.contentDescription = "时间点 ${time.format()}，点击修改"

            row.btnPickTime.setOnClickListener {
                TimePickerDialog(this, { _, h, m ->
                    // 检查是否与其他时间点重复（排除自己）
                    val duplicate = times.indices.any { it != index && times[it].hour == h && times[it].minute == m }
                    if (duplicate) {
                        Toast.makeText(this, "已存在相同的时间点 ${"%02d:%02d".format(h, m)}", Toast.LENGTH_SHORT).show()
                        return@TimePickerDialog
                    }
                    times[index] = ReminderTime(h, m)
                    renderTimes()
                }, time.hour, time.minute, true).show()
            }

            row.btnRemoveTime.setOnClickListener {
                if (times.size <= 1) {
                    Toast.makeText(this, "至少保留一个时间点", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                times.removeAt(index)
                renderTimes()
            }

            container.addView(row.root)
        }
    }

    /* ===================== 权限 ===================== */

    private fun checkExactAlarmPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                Toast.makeText(this, "请允许精确闹钟权限，否则提醒可能不准时", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM").apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
                return false
            }
        }
        return true
    }

    /* ===================== 显示 ===================== */

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
            repeatMinutes % 60 == 0 -> "${repeatMinutes / 60}小时"
            else -> "${repeatMinutes}分钟"
        }
    }

    companion object {
        const val EXTRA_IS_NEW = "is_new_drug"
    }
}
