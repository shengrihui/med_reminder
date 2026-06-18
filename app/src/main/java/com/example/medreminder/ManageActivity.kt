package com.example.medreminder

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import com.example.medreminder.databinding.ActivityManageBinding
import com.example.medreminder.databinding.ItemManageDrugBinding

/**
 * 管理页：药品列表
 *
 * - 列出所有药品，点击进入编辑
 * - 每个药品有开关
 * - 底部添加新药品（直接进入编辑页）
 * - 顶部测试通知按钮
 */
class ManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManageBinding

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "没有通知权限，提醒将无法显示", Toast.LENGTH_LONG).show()
            }
        }

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
            // 直接创建药品并进入编辑页，不先问名字
            val drug = DrugStore.addDrug(this, "新药品")
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                putExtra(ReminderManager.EXTRA_DRUG_ID, drug.id)
            })
        }

        binding.btnTestNotification.setOnClickListener {
            testNotification()
        }

        checkNotificationPermission()
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
            val timesText = drug.times.sortedBy { it.hour * 60 + it.minute }
                .joinToString("、") { it.format() }
            item.tvTimes.text = "$intervalText $timesText"

            item.swEnabled.isChecked = drug.enabled
            item.swEnabled.setOnCheckedChangeListener { _, isChecked ->
                val updated = drug.copy(enabled = isChecked)
                DrugStore.saveDrug(this, updated)
                if (isChecked) {
                    checkExactAlarmPermission()
                    ReminderManager.scheduleAllDailyAlarms(this, updated)
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

    /* ===================== 测试通知 ===================== */

    private fun testNotification() {
        val drugs = DrugStore.getAllDrugs(this)
        if (drugs.isEmpty()) {
            Toast.makeText(this, "请先添加药品", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        // 发送广播触发 AlarmReceiver，测试完整通知流程
        val drug = drugs.first()
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra(ReminderManager.EXTRA_DRUG_ID, drug.id)
            putExtra(ReminderManager.EXTRA_TIME_INDEX, 0)
        }
        sendBroadcast(intent)
        Toast.makeText(this, "已触发测试通知：${drug.name}", Toast.LENGTH_SHORT).show()
    }

    /* ===================== 权限 ===================== */

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

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
