package com.example.medreminder

import android.app.AlarmManager
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.medreminder.databinding.ActivityManageBinding
import com.example.medreminder.databinding.ItemManageDrugBinding

/**
 * 管理页：药品列表
 *
 * - 列出所有药品，点击进入编辑
 * - 每个药品有开关
 * - 底部添加新药品（直接进入编辑页）
 */
class ManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManageBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.btnAddNew.setOnClickListener {
            // 进入新建模式，保存时才真正创建药品
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                putExtra(SettingsActivity.EXTRA_IS_NEW, true)
            })
        }
    }

    override fun onResume() {
        super.onResume()
        renderList()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /* ===================== 列表渲染 ===================== */

    private fun renderList() {
        val drugs = DrugStore.getAllDrugs(this)
        val list = binding.llDrugList
        list.removeAllViews()

        if (drugs.isEmpty()) {
            binding.tvEmptyManage.visibility = View.VISIBLE
            return
        }
        binding.tvEmptyManage.visibility = View.GONE

        for (drug in drugs) {
            val item = ItemManageDrugBinding.inflate(LayoutInflater.from(this), list, false)
            item.tvName.text = drug.name

            val intervalText = when (drug.intervalDays) {
                1 -> "每天"
                2 -> "隔天"
                else -> "每${drug.intervalDays}天"
            }
            val schedulesText = drug.schedules.mapIndexed { index, schedule ->
                val times = schedule.reminderTimes.sortedBy { it.hour * 60 + it.minute }
                    .joinToString("、") { it.format() }
                "${schedule.displayName(index)} $times"
            }.joinToString("；")
            item.tvTimes.text = "$intervalText ${drug.schedules.size}次：$schedulesText"

            item.swEnabled.isChecked = drug.enabled
            item.swEnabled.setOnCheckedChangeListener { _, isChecked ->
                val updated = drug.copy(enabled = isChecked)
                DrugStore.saveDrug(this, updated)
                if (isChecked) {
                    if (checkExactAlarmPermission()) {
                        ReminderManager.scheduleAllAlarms(this, updated)
                    }
                } else {
                    ReminderManager.cancelAllAlarms(this, drug.id)
                }
            }

            // 点击卡片进入编辑（点开关除外）
            item.root.setOnClickListener {
                if (!item.swEnabled.isPressed) {
                    startActivity(Intent(this, SettingsActivity::class.java).apply {
                        putExtra(ReminderManager.EXTRA_DRUG_ID, drug.id)
                    })
                }
            }

            list.addView(item.root)
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
}
