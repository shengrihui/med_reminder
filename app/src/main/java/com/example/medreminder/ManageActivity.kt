package com.example.medreminder

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
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
 * - 底部添加新药品
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

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "管理药品"

        binding.btnAddNew.setOnClickListener {
            showAddDrugDialog()
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
            val timesText = drug.times.joinToString("、") { it.format() }
            item.tvTimes.text = "$intervalText $timesText"

            item.swEnabled.isChecked = drug.enabled
            item.swEnabled.setOnCheckedChangeListener { _, isChecked ->
                val updated = drug.copy(enabled = isChecked)
                DrugStore.saveDrug(this, updated)
                if (isChecked) {
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

    private fun showAddDrugDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "药品名称"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("添加药品")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "请输入药品名称", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val drug = DrugStore.addDrug(this, name)
                ReminderManager.scheduleAllDailyAlarms(this, drug)
                renderList()
                Toast.makeText(this, "已添加：$name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
