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
import com.example.medreminder.databinding.ItemHistoryTimeBinding
import java.util.Calendar

/**
 * 历史记录页
 *
 * 按药品分类，每个药品下按时间点列出。
 * 每个时间点只显示有操作（吃了或忽略）的日期记录。
 * 没有操作的日期不显示。
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

        for (drug in drugs) {
            val card = ItemHistoryDrugBinding.inflate(LayoutInflater.from(this), container, false)
            card.tvDrugName.text = drug.name

            // 按时间排序的时间点
            val sortedIndices = drug.times.indices.sortedBy { drug.times[it].hour * 60 + drug.times[it].minute }

            for (timeIndex in sortedIndices) {
                val timeView = ItemHistoryTimeBinding.inflate(LayoutInflater.from(this), card.llTimes, false)
                timeView.tvTime.text = drug.times[timeIndex].format()

                // 收集最近 14 天内有操作的记录
                val records = mutableListOf<Triple<String, Boolean, Calendar>>()
                val cal = Calendar.getInstance()
                for (i in 0 until 14) {
                    val dateStr = DrugStore.dateString(cal)
                    val isTaken = DrugStore.isTakenOn(this, drug.id, timeIndex, dateStr)
                    val isIgnored = DrugStore.isIgnoredOn(this, drug.id, timeIndex, dateStr)
                    if (isTaken || isIgnored) {
                        records.add(Triple(dateStr, isTaken, cal.clone() as Calendar))
                    }
                    cal.add(Calendar.DAY_OF_MONTH, -1)
                }

                if (records.isEmpty()) {
                    timeView.tvNoRecord.visibility = android.view.View.VISIBLE
                } else {
                    timeView.tvNoRecord.visibility = android.view.View.GONE
                    val todayStr = DrugStore.dateString(Calendar.getInstance())
                    for ((dateStr, isTaken, dayCal) in records) {
                        val row = ItemHistoryRecordBinding.inflate(LayoutInflater.from(this), timeView.llRecords, false)
                        val monthDay = "${dayCal.get(Calendar.MONTH) + 1}月${dayCal.get(Calendar.DAY_OF_MONTH)}日"
                        val isToday = dateStr == todayStr
                        row.tvDate.text = if (isToday) "$monthDay 今天" else monthDay

                        if (isTaken) {
                            row.tvStatus.text = "已吃"
                            row.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_taken))
                            row.dotStatus.setBackgroundResource(R.drawable.dot_taken)
                        } else {
                            row.tvStatus.text = "已忽略"
                            row.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_ignored))
                            row.dotStatus.setBackgroundResource(R.drawable.dot_ignored)
                        }

                        row.root.contentDescription = "$monthDay ${if (isToday) "今天" else ""} ${row.tvStatus.text}"
                        timeView.llRecords.addView(row.root)
                    }
                }

                card.llTimes.addView(timeView.root)
            }

            container.addView(card.root)
        }
    }
}
