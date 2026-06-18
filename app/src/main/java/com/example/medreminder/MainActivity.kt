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
import com.example.medreminder.databinding.ItemCompletedRowBinding
import com.example.medreminder.databinding.ItemDrugCardBinding
import com.example.medreminder.databinding.ItemTimeRowBinding

/**
 * 首页
 *
 * - 上半部分：今天还没吃完的药（每个时间点有"吃了""忽略"按钮）
 * - 下半部分：今日已完成（可"撤销"，避免误操作导致药品消失）
 * - 顶部右侧：历史、管理 入口
 * - 底部：添加药品
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
            // 直接创建药品并进入编辑页
            val drug = DrugStore.addDrug(this, "新药品")
            if (drug == null) {
                Toast.makeText(this, "已存在同名药品，请改名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                putExtra(ReminderManager.EXTRA_DRUG_ID, drug.id)
            })
        }

        binding.btnManage.setOnClickListener {
            startActivity(Intent(this, ManageActivity::class.java))
        }

        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
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
        binding.llCompletedList.removeAllViews()

        val enabledDrugs = drugs.filter { it.enabled }

        // 待吃药：还有时间点未完成
        val pendingDrugs = enabledDrugs.filter { !DrugStore.isAllCompleted(this, it) }

        // 已完成：所有时间点都已完成
        val completedDrugs = enabledDrugs.filter { DrugStore.isAllCompleted(this, it) }

        // 空状态
        binding.tvEmpty.visibility = if (enabledDrugs.isEmpty()) View.VISIBLE else View.GONE

        // 按最早未完成时间点排序
        val sortedPending = pendingDrugs.sortedBy { drug ->
            drug.times.indices
                .filter { !DrugStore.isCompleted(this, drug.id, it) }
                .minOfOrNull { drug.times[it].hour * 60 + drug.times[it].minute }
                ?: Int.MAX_VALUE
        }

        for (drug in sortedPending) {
            val card = ItemDrugCardBinding.inflate(LayoutInflater.from(this), list, false)
            bindDrugCard(card, drug)
            list.addView(card.root)
        }

        // 渲染"今日已完成"区域
        if (completedDrugs.isNotEmpty()) {
            binding.llCompletedSection.visibility = View.VISIBLE
            for (drug in completedDrugs) {
                bindCompletedSection(drug)
            }
        } else {
            binding.llCompletedSection.visibility = View.GONE
        }
    }

    private fun bindDrugCard(card: ItemDrugCardBinding, drug: Drug) {
        card.tvDrugName.text = drug.name

        val remaining = DrugStore.remainingCount(this, drug)
        card.tvRemaining.text = "${remaining}次未吃"

        val timesContainer = card.llTimes
        timesContainer.removeAllViews()

        // 按时间排序索引（只显示未完成的）
        val sortedIndices = drug.times.indices
            .filter { !DrugStore.isCompleted(this, drug.id, it) }
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

    private fun bindCompletedSection(drug: Drug) {
        // 显示该药品今天已完成的所有时间点（按时间排序）
        val sortedIndices = drug.times.indices
            .filter { DrugStore.isCompleted(this, drug.id, it) }
            .sortedBy { drug.times[it].hour * 60 + drug.times[it].minute }

        for (i in sortedIndices) {
            val row = ItemCompletedRowBinding.inflate(
                LayoutInflater.from(this), binding.llCompletedList, false
            )
            row.tvDrugName.text = "${drug.name}  ${drug.times[i].format()}"
            val isTaken = DrugStore.isTaken(this, drug.id, i)
            row.tvStatus.text = if (isTaken) "已吃" else "已忽略"
            row.tvStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (isTaken) R.color.status_taken else R.color.text_hint
                )
            )
            row.root.contentDescription = "${drug.name} ${drug.times[i].format()}，${if (isTaken) "已吃" else "已忽略"}，点击撤销"

            row.btnUndo.setOnClickListener {
                DrugStore.undoCompleted(this, drug.id, i)
                // 撤销后重新注册今日闹钟（如果今天时间还没过）或下次提醒
                DrugStore.getDrug(this, drug.id)?.let { d ->
                    ReminderManager.scheduleDailyAlarm(this, d, i)
                }
                renderDrugList()
                Toast.makeText(this, "已撤销：${drug.name} ${drug.times[i].format()}", Toast.LENGTH_SHORT).show()
            }

            binding.llCompletedList.addView(row.root)
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
