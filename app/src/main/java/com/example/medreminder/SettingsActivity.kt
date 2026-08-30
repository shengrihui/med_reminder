package com.example.medreminder

import android.app.AlarmManager
import android.app.DatePickerDialog
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
import androidx.core.widget.doAfterTextChanged
import com.example.medreminder.databinding.ActivitySettingsBinding
import com.example.medreminder.databinding.ItemDoseScheduleEditBinding
import com.example.medreminder.databinding.ItemTimeEditBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 编辑药品页
 *
 * 两种模式：
 * - 编辑模式：drugId >= 0，加载已有药品配置，可保存/删除
 * - 新建模式：drugId = -1（EXTRA_IS_NEW），用默认配置初始化，保存时才真正创建药品；
 *   未保存直接返回则不会创建任何药品。
 *
 * - 支持一天多次服药、每次服药多个提醒时间点
 * - 服用频率、重复间隔（步进器）
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var drugId: Int = -1
    private var isNew: Boolean = false
    private var drug: Drug? = null

    private var schedules = mutableListOf<DoseSchedule>()
    private var intervalDays = Drug.DEFAULT_INTERVAL_DAYS
    private var repeatMinutes = Drug.DEFAULT_REPEAT_MINUTES
    private var startDate = DrugStore.dateString(Calendar.getInstance())

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
        renderSchedules()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /* ===================== 加载 ===================== */

    /** 新建模式：用默认值初始化 */
    private fun initDefaults() {
        schedules = mutableListOf(DoseSchedule(1, listOf(ReminderTime.DEFAULT)))
        intervalDays = Drug.DEFAULT_INTERVAL_DAYS
        repeatMinutes = Drug.DEFAULT_REPEAT_MINUTES
        startDate = DrugStore.dateString(Calendar.getInstance())
        binding.etMedName.setText("")
        binding.etMedName.hint = "例如：阿司匹林"
        updateIntervalDisplay()
        updateRepeatDisplay()
        updateStartDateDisplay()
    }

    /** 编辑模式：加载已有药品配置 */
    private fun loadConfig() {
        val d = drug ?: return
        schedules = d.schedules.toMutableList()
        intervalDays = d.intervalDays
        repeatMinutes = d.repeatMinutes
        startDate = d.startDate
        binding.etMedName.setText(d.name)
        updateIntervalDisplay()
        updateRepeatDisplay()
        updateStartDateDisplay()
    }

    /* ===================== 监听 ===================== */

    private fun setupListeners() {
        binding.btnAddSchedule.setOnClickListener {
            val nextKey = newScheduleKey()
            schedules.add(DoseSchedule(nextKey, listOf(nextAvailableTime())))
            renderSchedules()
            Toast.makeText(this, "已添加第${schedules.size}次服药", Toast.LENGTH_SHORT).show()
        }

        binding.btnIntervalMinus.setOnClickListener {
            if (intervalDays > 1) { intervalDays--; updateIntervalDisplay() }
        }
        binding.btnIntervalPlus.setOnClickListener {
            if (intervalDays < 30) { intervalDays++; updateIntervalDisplay() }
        }

        binding.llStartDate.setOnClickListener {
            if (intervalDays == 1) return@setOnClickListener
            openStartDatePicker()
        }

        binding.btnRepeatMinus.setOnClickListener {
            if (repeatMinutes > 0) { repeatMinutes -= 5; updateRepeatDisplay() }
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
            if (schedules.isEmpty() || schedules.any { it.reminderTimes.isEmpty() }) {
                Toast.makeText(this, "至少需要一次服药，并为每次服药设置提醒时间", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isNew) {
                // 新建模式：保存时才创建
                if (DrugStore.isDrugNameDuplicate(this, name, excludeId = -1)) {
                    Toast.makeText(this, "已存在同名药品「$name」，请改名", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val created = DrugStore.createDrug(
                    this, name, schedules.toList(), intervalDays, repeatMinutes,
                    if (intervalDays == 1) DrugStore.dateString(Calendar.getInstance()) else startDate
                )
                if (created == null) {
                    Toast.makeText(this, "创建失败，请重试", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // 注册闹钟；权限不足时仍然保存药品，并提示用户去授权
                if (checkExactAlarmPermission()) {
                    ReminderManager.scheduleAllAlarms(this, created)
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
                    schedules = schedules.toList(),
                    intervalDays = intervalDays,
                    repeatMinutes = repeatMinutes,
                    startDate = if (intervalDays == 1) DrugStore.dateString(Calendar.getInstance()) else startDate
                )
                // 必须在写入新配置前取消旧配置的闹钟，才能覆盖被删除的时段和时间点。
                ReminderManager.cancelAllAlarms(this, d.id)
                DrugStore.saveDrug(this, updated)
                // 重新注册闹钟；权限不足时仍然保存，并提示用户去授权
                if (updated.enabled) {
                    if (checkExactAlarmPermission()) {
                        ReminderManager.scheduleAllAlarms(this, updated)
                    }
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

    /* ===================== 服药安排与提醒时间 ===================== */

    private fun renderSchedules() {
        val container = binding.llSchedules
        container.removeAllViews()

        schedules.forEachIndexed { scheduleIndex, schedule ->
            val scheduleBinding = ItemDoseScheduleEditBinding.inflate(
                LayoutInflater.from(this), container, false
            )
            val scheduleName = schedule.displayName(scheduleIndex)
            scheduleBinding.tvScheduleName.text = scheduleName
            ViewCompat.setAccessibilityHeading(scheduleBinding.tvScheduleName, true)
            scheduleBinding.etScheduleName.setText(schedule.customName)
            scheduleBinding.etScheduleName.contentDescription =
                "${scheduleName}的自定义名称，留空使用第${scheduleIndex + 1}次服药"
            scheduleBinding.etScheduleName.doAfterTextChanged { editable ->
                updateSchedule(schedule.scheduleKey) { current ->
                    current.copy(customName = editable?.toString().orEmpty())
                }
            }
            scheduleBinding.btnRemoveSchedule.contentDescription = "删除$scheduleName"
            scheduleBinding.btnRemoveSchedule.setOnClickListener {
                if (schedules.size <= 1) {
                    Toast.makeText(this, "至少保留一次服药安排", Toast.LENGTH_SHORT).show()
                } else {
                    schedules.removeAll { it.scheduleKey == schedule.scheduleKey }
                    renderSchedules()
                }
            }

            schedule.reminderTimes.sortedBy { it.hour * 60 + it.minute }.forEach { time ->
                val row = ItemTimeEditBinding.inflate(
                    LayoutInflater.from(this), scheduleBinding.llReminderTimes, false
                )
                row.btnPickTime.text = time.format()
                row.btnPickTime.contentDescription = "${scheduleName}提醒时间${spokenTime(time)}，点击修改"
                row.btnPickTime.setOnClickListener {
                    TimePickerDialog(this, { _, hour, minute ->
                        val replacement = ReminderTime(hour, minute)
                        val duplicate = schedules.any { candidate ->
                            candidate.reminderTimes.any { existing ->
                                existing == replacement &&
                                    !(candidate.scheduleKey == schedule.scheduleKey && existing == time)
                            }
                        }
                        if (duplicate) {
                            Toast.makeText(this, "已存在相同提醒时间 ${replacement.format()}", Toast.LENGTH_SHORT).show()
                            return@TimePickerDialog
                        }
                        updateSchedule(schedule.scheduleKey) { current ->
                            current.copy(reminderTimes = current.reminderTimes.map {
                                if (it == time) replacement else it
                            })
                        }
                        renderSchedules()
                    }, time.hour, time.minute, true).show()
                }
                row.btnRemoveTime.contentDescription = "删除${scheduleName}的${spokenTime(time)}提醒"
                row.btnRemoveTime.setOnClickListener {
                    if (schedule.reminderTimes.size <= 1) {
                        Toast.makeText(this, "每次服药至少保留一个提醒时间", Toast.LENGTH_SHORT).show()
                    } else {
                        updateSchedule(schedule.scheduleKey) { current ->
                            current.copy(reminderTimes = current.reminderTimes.filterNot { it == time })
                        }
                        renderSchedules()
                    }
                }
                scheduleBinding.llReminderTimes.addView(row.root)
            }

            scheduleBinding.btnAddReminderTime.contentDescription = "为${scheduleName}添加提醒时间"
            scheduleBinding.btnAddReminderTime.setOnClickListener {
                val newTime = nextAvailableTime()
                updateSchedule(schedule.scheduleKey) { current ->
                    current.copy(reminderTimes = current.reminderTimes + newTime)
                }
                renderSchedules()
            }
            container.addView(scheduleBinding.root)
        }
    }

    private fun updateSchedule(scheduleKey: Int, transform: (DoseSchedule) -> DoseSchedule) {
        val index = schedules.indexOfFirst { it.scheduleKey == scheduleKey }
        if (index >= 0) schedules[index] = transform(schedules[index])
    }

    private fun nextAvailableTime(): ReminderTime {
        val used = schedules.flatMap { it.reminderTimes }.toSet()
        val calendar = Calendar.getInstance().apply { add(Calendar.MINUTE, 1) }
        repeat(24 * 60) {
            val candidate = ReminderTime(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
            if (candidate !in used) return candidate
            calendar.add(Calendar.MINUTE, 1)
        }
        return ReminderTime.DEFAULT
    }

    /** 不复用已删除安排的内部键，避免新安排与旧历史记录串联。 */
    private fun newScheduleKey(): Int {
        val used = schedules.map { it.scheduleKey }.toSet()
        var candidate = (System.currentTimeMillis() and 0x7fffffff).toInt().coerceAtLeast(1)
        while (candidate in used) candidate = if (candidate == Int.MAX_VALUE) 1 else candidate + 1
        return candidate
    }

    private fun spokenTime(time: ReminderTime): String = "${time.hour}点${time.minute}分"

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
        updateStartDateDisplay()
    }

    private fun updateRepeatDisplay() {
        binding.tvRepeatValue.text = when {
            repeatMinutes == 0 -> "不重复"
            repeatMinutes < 60 -> "${repeatMinutes}分钟"
            repeatMinutes % 60 == 0 -> "${repeatMinutes / 60}小时"
            else -> "${repeatMinutes}分钟"
        }
    }

    private fun updateStartDateDisplay() {
        val isDaily = intervalDays == 1
        if (isDaily) {
            binding.tvStartDateValue.text = "今天（每天）"
            binding.llStartDate.isEnabled = false
            binding.llStartDate.isClickable = false
            binding.llStartDate.alpha = 0.4f
            binding.tvStartDateLabel.alpha = 0.4f
        } else {
            binding.tvStartDateValue.text = startDate
            binding.llStartDate.isEnabled = true
            binding.llStartDate.isClickable = true
            binding.llStartDate.alpha = 1f
            binding.tvStartDateLabel.alpha = 1f
        }
    }

    private fun openStartDatePicker() {
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val cal = Calendar.getInstance().apply {
            time = parser.parse(startDate) ?: Date()
        }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                startDate = String.format(Locale.CHINA, "%04d-%02d-%02d", year, month + 1, dayOfMonth)
                updateStartDateDisplay()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    companion object {
        const val EXTRA_IS_NEW = "is_new_drug"
    }
}
