package com.example.medreminder

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import java.util.Calendar

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

        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        // 过滤：只显示 enabled 且有"未过+未完成"时间点的药品
        val pendingDrugs = drugs.filter { drug ->
            if (!drug.enabled) return@filter false
            drug.times.indices.any { i ->
                val time = drug.times[i]
                val isCompleted = DrugStore.isCompleted(this, drug.id, i)
                val isPast = (time.hour * 60 + time.minute) < nowMinutes
                !isCompleted && !isPast
            }
        }

        if (pendingDrugs.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }
        binding.tvEmpty.visibility = View.GONE

        // 按最早未完成且未过期的时间点排序
        val sortedPending = pendingDrugs.sortedBy { drug ->
            drug.times.indices
                .filter { i ->
                    val time = drug.times[i]
                    val isCompleted = DrugStore.isCompleted(this, drug.id, i)
                    val isPast = (time.hour * 60 + time.minute) < nowMinutes
                    !isCompleted && !isPast
                }
                .minOfOrNull { drug.times[it].hour * 60 + drug.times[it].minute }
                ?: Int.MAX_VALUE
        }

        for (drug in sortedPending) {
            val card = ItemDrugCardBinding.inflate(LayoutInflater.from(this), list, false)
            bindDrugCard(card, drug, nowMinutes)
            list.addView(card.root)
        }
    }

    private fun bindDrugCard(card: ItemDrugCardBinding, drug: Drug, nowMinutes: Int) {
        card.tvDrugName.text = drug.name

        // 只统计"未过且未完成"的时间点
        val pending = drug.times.indices.count { i ->
            val time = drug.times[i]
            val isCompleted = DrugStore.isCompleted(this, drug.id, i)
            val isPast = (time.hour * 60 + time.minute) < nowMinutes
            !isCompleted && !isPast
        }
        card.tvRemaining.text = "${pending}次未吃"

        val timesContainer = card.llTimes
        timesContainer.removeAllViews()

        // 按时间排序（只显示"未过且未完成"）
        val sortedIndices = drug.times.indices
            .filter { i ->
                val time = drug.times[i]
                val isCompleted = DrugStore.isCompleted(this, drug.id, i)
                val isPast = (time.hour * 60 + time.minute) < nowMinutes
                !isCompleted && !isPast
            }
            .sortedBy { drug.times[it].hour * 60 + drug.times[it].minute }

        for (i in sortedIndices) {
            val row = ItemTimeRowBinding.inflate(LayoutInflater.from(this), timesContainer, false)
            row.tvTime.text = drug.times[i].format()
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

            // 忽略（不再提醒，但不算已吃，历史记录不计入已吃）
            row.btnIgnore.setOnClickListener {
                DrugStore.markIgnored(this, drug.id, i)
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
}
