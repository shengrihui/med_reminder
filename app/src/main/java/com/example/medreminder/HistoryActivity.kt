package com.example.medreminder

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.gridlayout.widget.GridLayout
import com.example.medreminder.databinding.ActivityHistoryBinding
import com.example.medreminder.databinding.ItemHistoryDrugBinding
import com.example.medreminder.databinding.ItemHistoryTimeBinding
import java.util.Calendar

/**
 * 历史记录页
 *
 * 按药品分组，每个药品下按时间点显示最近 14 天的记录。
 * 每天一个小方格：
 * - 绿色 = 已吃
 * - 橙色 = 已忽略
 * - 灰色 = 未吃/未记录
 *
 * 统计只计算"已吃"次数，已忽略不算入已吃。
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
                textSize = 16f
                setPadding(40, 80, 40, 80)
            }
            container.addView(tv)
            return
        }

        // 图例
        val legend = TextView(this).apply {
            text = "图例：绿=已吃  橙=已忽略  灰=未记录"
            setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_hint))
            textSize = 12f
            setPadding(0, 0, 0, 24)
        }
        container.addView(legend)

        for (drug in drugs) {
            val card = ItemHistoryDrugBinding.inflate(LayoutInflater.from(this), container, false)
            card.tvDrugName.text = drug.name

            // 统计最近 14 天已吃次数（忽略不算已吃）
            val cal = Calendar.getInstance()
            var takenCount = 0
            var totalSlots = 0
            for (i in 0 until 14) {
                for (timeIndex in drug.times.indices) {
                    totalSlots++
                    if (DrugStore.isTakenOn(this, drug.id, timeIndex, DrugStore.dateString(cal))) {
                        takenCount++
                    }
                }
                cal.add(Calendar.DAY_OF_MONTH, -1)
            }
            card.tvSummary.text = "最近14天已吃 $takenCount/$totalSlots 次"

            // 每个时间点的历史网格
            for (timeIndex in drug.times.indices) {
                val timeView = ItemHistoryTimeBinding.inflate(LayoutInflater.from(this), card.llTimes, false)
                timeView.tvTime.text = drug.times[timeIndex].format()
                renderTimeGrid(timeView.gridHistory, drug.id, timeIndex)
                card.llTimes.addView(timeView.root)
            }

            container.addView(card.root)
        }
    }

    private fun renderTimeGrid(grid: GridLayout, drugId: Int, timeIndex: Int) {
        grid.removeAllViews()

        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -13) }
        val weekNames = arrayOf("日", "一", "二", "三", "四", "五", "六")

        // 第一行：星期标题
        for (w in weekNames) {
            val tv = TextView(this).apply {
                text = w
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_hint))
                textSize = 11f
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
            }
            grid.addView(tv, params)
        }

        // 14 天
        for (i in 0 until 14) {
            val dateStr = DrugStore.dateString(cal)
            val isTaken = DrugStore.isTakenOn(this, drugId, timeIndex, dateStr)
            val isIgnored = DrugStore.isIgnoredOn(this, drugId, timeIndex, dateStr)
            val isToday = dateStr == DrugStore.dateString(Calendar.getInstance())
            val monthDay = "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}"

            val tv = TextView(this).apply {
                text = monthDay
                gravity = Gravity.CENTER
                textSize = 10f
                setPadding(4, 8, 4, 8)
                when {
                    isTaken -> {
                        setBackgroundResource(R.drawable.bg_history_taken)
                        setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.bg_card))
                    }
                    isIgnored -> {
                        setBackgroundResource(R.drawable.bg_history_ignored)
                        setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.bg_card))
                    }
                    else -> {
                        setBackgroundResource(R.drawable.bg_history_missed)
                        setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_hint))
                    }
                }
                if (isToday) {
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                }
                val statusText = when {
                    isTaken -> "已吃"
                    isIgnored -> "已忽略"
                    else -> "未记录"
                }
                contentDescription = "$monthDay $statusText${if (isToday) " 今天" else ""}"
            }

            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                if (i % 7 == 0) topMargin = 4
            }
            grid.addView(tv, params)
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
    }
}
