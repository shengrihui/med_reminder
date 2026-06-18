package com.example.medreminder

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.medreminder.databinding.ActivityHistoryBinding
import com.example.medreminder.databinding.ItemHistoryDrugBinding
import com.example.medreminder.databinding.ItemHistoryRecordBinding
import java.util.Calendar

/**
 * 历史记录页
 *
 * 按药品分类，每个药品下平铺列出"日期+时间点+状态"的记录。
 * 只显示有操作（吃了或忽略）的记录，按时间倒序（最新在上）。
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding

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

        renderHistory()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * 一条历史记录
     */
    private data class HistoryEntry(
        val dateStr: String,
        val calendar: Calendar,
        val timeIndex: Int,
        val timeStr: String,
        val isTaken: Boolean
    )

    private fun renderHistory() {
        val container = binding.llHistory
        container.removeAllViews()

        val drugs = DrugStore.getAllDrugs(this)
        if (drugs.isEmpty()) {
            val tv = TextView(this).apply {
                text = "还没有药品"
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_hint))
                textSize = 17f
                setPadding(40, 80, 40, 80)
            }
            container.addView(tv)
            return
        }

        // 图例
        val legend = TextView(this).apply {
            text = "●绿=已吃  ●橙=已忽略"
            setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_hint))
            textSize = 14f
            setPadding(0, 0, 0, 20)
        }
        container.addView(legend)

        val todayStr = DrugStore.dateString(Calendar.getInstance())

        for (drug in drugs) {
            val card = ItemHistoryDrugBinding.inflate(LayoutInflater.from(this), container, false)
            card.tvDrugName.text = drug.name

            // 收集该药品所有有操作的记录（按时间倒序：最新在上）
            val entries = mutableListOf<HistoryEntry>()
            val cal = Calendar.getInstance()
            for (i in 0 until 14) {
                val dateStr = DrugStore.dateString(cal)
                for (timeIndex in drug.times.indices) {
                    val isTaken = DrugStore.isTakenOn(this, drug.id, timeIndex, dateStr)
                    val isIgnored = DrugStore.isIgnoredOn(this, drug.id, timeIndex, dateStr)
                    if (isTaken || isIgnored) {
                        entries.add(
                            HistoryEntry(
                                dateStr = dateStr,
                                calendar = cal.clone() as Calendar,
                                timeIndex = timeIndex,
                                timeStr = drug.times[timeIndex].format(),
                                isTaken = isTaken
                            )
                        )
                    }
                }
                cal.add(Calendar.DAY_OF_MONTH, -1)
            }

            if (entries.isEmpty()) {
                card.tvNoRecord.visibility = android.view.View.VISIBLE
            } else {
                card.tvNoRecord.visibility = android.view.View.GONE
                for (entry in entries) {
                    val row = ItemHistoryRecordBinding.inflate(LayoutInflater.from(this), card.llRecords, false)
                    val monthDay = "${entry.calendar.get(Calendar.MONTH) + 1}月${entry.calendar.get(Calendar.DAY_OF_MONTH)}日"
                    val isToday = entry.dateStr == todayStr
                    val dateLabel = if (isToday) "$monthDay 今天" else monthDay
                    row.tvDateTime.text = "$dateLabel  $entry.timeStr"

                    if (entry.isTaken) {
                        row.tvStatus.text = "已吃"
                        row.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_taken))
                        row.dotStatus.setBackgroundResource(R.drawable.dot_taken)
                    } else {
                        row.tvStatus.text = "已忽略"
                        row.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_ignored))
                        row.dotStatus.setBackgroundResource(R.drawable.dot_ignored)
                    }

                    row.root.contentDescription = "$dateLabel $entry.timeStr ${row.tvStatus.text}"
                    card.llRecords.addView(row.root)
                }
            }

            container.addView(card.root)
        }
    }
}
