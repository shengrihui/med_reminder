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

/**
 * 首页
 *
 * - 只显示今天还没吃完的药
 * - 每个卡片直接展开显示未完成的时间点，每个时间点有"吃了""忽略"按钮
 * - 底部"管理药品"按钮 → 进入管理页
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

        binding.btnAddDrug.setOnClickListener {
            startActivity(Intent(this, ManageActivity::class.java))
        }

        checkNotificationPermission()
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

        // 只显示今天还没吃完的药
        val pendingDrugs = drugs.filter { it.enabled && !DrugStore.isAllTaken(this, it) }

        if (pendingDrugs.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }
        binding.tvEmpty.visibility = View.GONE

        // 按最早未完成时间点排序
        val sortedDrugs = pendingDrugs.sortedBy { drug ->
            drug.times.indices
                .filter { !DrugStore.isTaken(this, drug.id, it) }
                .minOfOrNull { drug.times[it].hour * 60 + drug.times[it].minute }
                ?: Int.MAX_VALUE
        }

        for (drug in sortedDrugs) {
            val card = ItemDrugCardBinding.inflate(LayoutInflater.from(this), list, false)
            bindDrugCard(card, drug)
            list.addView(card.root)
        }
    }

    private fun bindDrugCard(card: ItemDrugCardBinding, drug: Drug) {
        card.tvDrugName.text = drug.name

        val remaining = DrugStore.remainingCount(this, drug)
        card.tvRemaining.text = "${remaining}次未吃"

        // 渲染未完成的时间点（按时间排序）
        val timesContainer = card.llTimes
        timesContainer.removeAllViews()

        // 按时间排序索引
        val sortedIndices = drug.times.indices
            .filter { !DrugStore.isTaken(this, drug.id, it) }
            .sortedBy { drug.times[it].hour * 60 + drug.times[it].minute }

        for (i in sortedIndices) {
            val row = ItemTimeRowBinding.inflate(LayoutInflater.from(this), timesContainer, false)
            row.tvTime.text = drug.times[i].format()

            // 无障碍
            row.root.contentDescription = "${drug.name} ${drug.times[i].format()}，未吃药"

            // 吃了
            row.btnTaken.setOnClickListener {
                DrugStore.markTaken(this, drug.id, i)
                ReminderManager.cancelRepeatAlarm(this, drug.id, i)
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(ReminderManager.notificationId(drug.id, i))
                renderDrugList()
                Toast.makeText(this, "${drug.name} ${drug.times[i].format()}：已标记吃药", Toast.LENGTH_SHORT).show()
            }

            // 忽略（和吃了效果一样，标记今天不用再提醒这个时间点）
            row.btnIgnore.setOnClickListener {
                DrugStore.markTaken(this, drug.id, i)
                ReminderManager.cancelRepeatAlarm(this, drug.id, i)
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(ReminderManager.notificationId(drug.id, i))
                renderDrugList()
                Toast.makeText(this, "${drug.name} ${drug.times[i].format()}：已忽略", Toast.LENGTH_SHORT).show()
            }

            timesContainer.addView(row.root)
        }
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

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                Toast.makeText(this, "请允许精确闹钟权限，否则提醒可能不准时", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM").apply { data = Uri.parse("package:$packageName") })
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:$packageName") })
                }
            }
        }
    }
}
