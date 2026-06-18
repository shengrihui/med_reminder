package com.example.medreminder

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.medreminder.databinding.ActivityHistoryBinding
import com.example.medreminder.databinding.ItemHistoryRecordBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 历史记录页
 *
 * 按药品分组，每组内按实际操作时间倒序（最新操作在最上面，越早越在下面）。
 * 默认显示最近 20 条记录，点击“查看全部”可进入全部记录页。
 */
class HistoryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SHOW_ALL = "show_all"
        private const val PREVIEW_LIMIT = 20
    }

    private lateinit var binding: ActivityHistoryBinding
    private val showAll by lazy { intent.getBooleanExtra(EXTRA_SHOW_ALL, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = if (showAll) "全部历史记录" else "历史记录"

        renderHistory()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun renderHistory() {
        val container = binding.llHistory
        container.removeAllViews()

        val allRecords = DrugStore.getHistoryRecords(this)
        val previewRecords = if (showAll) allRecords else allRecords.take(PREVIEW_LIMIT)

        if (previewRecords.isEmpty()) {
            val tv = TextView(this).apply {
                text = "还没有记录"
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_hint))
                textSize = 17f
                setPadding(40, 100, 40, 100)
            }
            container.addView(tv)
            return
        }

        // 按预览顺序分组，保持“有最新记录的药品排在最前”的顺序
        val groups = LinkedHashMap<Int, MutableList<DrugStore.HistoryRecord>>()
        for (entry in previewRecords) {
            groups.getOrPut(entry.drugId) { mutableListOf() }.add(entry)
        }

        val todayStr = DrugStore.dateString(Calendar.getInstance())
        val dateParser = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

        for ((drugId, entries) in groups) {
            // 每组内部按操作时间倒序：新的在上， Old在下
            entries.sortByDescending { recordTimestamp(it) }
            val drugName = entries.first().drugName

            // 药品名分组标题 + 清空按钮
            val headerRow = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 24, 0, 8)
            }
            val header = TextView(this).apply {
                text = drugName
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_primary))
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            headerRow.addView(header, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val btnClear = TextView(this).apply {
                text = "清空"
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_hint))
                textSize = 17f
                setPadding(32, 6, 4, 6)
                setOnClickListener {
                    AlertDialog.Builder(this@HistoryActivity)
                        .setTitle("清空 $drugName 的全部记录")
                        .setMessage("确定要删除\"$drugName\"的全部历史记录吗？此操作不可撤销。")
                        .setPositiveButton("清空") { _, _ ->
                            DrugStore.deleteAllRecordsForDrug(this@HistoryActivity, drugId)
                            renderHistory()
                            Toast.makeText(this@HistoryActivity, "已清空\"$drugName\"的全部记录", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            headerRow.addView(btnClear)
            container.addView(headerRow)

            for (entry in entries) {
                val row = ItemHistoryRecordBinding.inflate(LayoutInflater.from(this), container, false)
                val displayTime = entry.actualTimeStr.take(5)

                val cal = Calendar.getInstance().apply {
                    val parsed = dateParser.parse(entry.dateStr)
                    if (parsed != null) time = parsed
                }
                val monthDay = "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
                val dateLabel = if (entry.dateStr == todayStr) "$monthDay 今天" else monthDay

                row.tvDateTime.text = displayTime
                row.tvScheduled.text = "$dateLabel  计划${entry.scheduledTime.format()}"

                if (entry.isTaken) {
                    row.tvStatus.text = "已吃"
                    row.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_taken))
                    row.dotStatus.setBackgroundResource(R.drawable.dot_taken)
                } else {
                    row.tvStatus.text = "已忽略"
                    row.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_ignored))
                    row.dotStatus.setBackgroundResource(R.drawable.dot_ignored)
                }

                row.btnDelete.setOnClickListener {
                    AlertDialog.Builder(this@HistoryActivity)
                        .setTitle("删除记录")
                        .setMessage("确定要删除 ${entry.drugName} ${dateLabel} ${displayTime} 的记录吗？")
                        .setPositiveButton("删除") { _, _ ->
                            DrugStore.deleteRecord(this@HistoryActivity, entry.drugId, entry.scheduledTime, entry.dateStr)
                            renderHistory()
                            Toast.makeText(this@HistoryActivity, "已删除", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }

                row.root.contentDescription = "${entry.drugName} $dateLabel $displayTime ${row.tvStatus.text}"
                container.addView(row.root)
            }
        }

        if (!showAll && allRecords.size > PREVIEW_LIMIT) {
            val btn = Button(this).apply {
                text = "查看全部 (${allRecords.size} 条)"
                textSize = 16f
                setOnClickListener {
                    startActivity(Intent(this@HistoryActivity, HistoryActivity::class.java).apply {
                        putExtra(EXTRA_SHOW_ALL, true)
                    })
                }
            }
            container.addView(btn)
        }
    }

    private fun recordTimestamp(record: DrugStore.HistoryRecord): Long {
        return try {
            val dateParts = record.dateStr.split("-").map { it.toInt() }
            val timeParts = record.actualTimeStr.split(":").map { it.toInt() }
            val second = if (timeParts.size >= 3) timeParts[2] else 0
            Calendar.getInstance().apply {
                set(dateParts[0], dateParts[1] - 1, dateParts[2],
                    timeParts[0], timeParts[1], second)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } catch (e: Exception) {
            0L
        }
    }
}
