package com.example.medreminder

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.medreminder.databinding.ActivityHistoryBinding
import com.example.medreminder.databinding.ItemHistoryDayBinding
import com.example.medreminder.databinding.ItemHistoryDrugBinding
import java.util.Calendar

/**
 * 历史记录页
 *
 * 按药品分组，每个药品一个卡片，里面是最近 14 天的列表。
 * 每行一天：日期 + 各时间点状态圆点 + 汇总。
 *
 * 圆点颜色：绿=已吃  橙=已忽略  灰=未记录
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
            text = "图例：●绿=已吃  ●橙=已忽略  ●灰=未记录"
            setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_hint))
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        container.addView(legend)

        for (drug in drugs) {
            val card = ItemHistoryDrugBinding.inflate(LayoutInflater.from(this), container, false)
            card.tvDrugName.text = drug.name

            // 统计最近 14 天已吃次数
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

            // 14 天列表（从今天往前）
            val today = Calendar.getInstance()
            for (i in 0 until 14) {
                val dayCal = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, -i)
                }
                val row = ItemHistoryDayBinding.inflate(LayoutInflater.from(this), card.llTimes, false)
                bindDayRow(row, drug, dayCal, i == 0)
                card.llTimes.addView(row.root)
            }

            container.addView(card.root)
        }
    }

    private fun bindDayRow(row: ItemHistoryDayBinding, drug: Drug, cal: Calendar, isToday: Boolean) {
        val dateStr = DrugStore.dateString(cal)
        val monthDay = "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}"
        val dayLabel = if (isToday) "$monthDay 今天" else monthDay
        row.tvDate.text = dayLabel

        // 各时间点圆点
        row.llDots.removeAllViews()
        var taken = 0
        var ignored = 0
        val totalTimes = drug.times.size

        for (timeIndex in drug.times.indices) {
            val isTaken = DrugStore.isTakenOn(this, drug.id, timeIndex, dateStr)
            val isIgnored = DrugStore.isIgnoredOn(this, drug.id, timeIndex, dateStr)
            if (isTaken) taken++
            if (isIgnored) ignored++

            val dot = View(this).apply {
                val drawableRes = when {
                    isTaken -> R.drawable.dot_taken
                    isIgnored -> R.drawable.dot_ignored
                    else -> R.drawable.dot_missed
                }
                setBackgroundResource(drawableRes)
                val size = (12 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (6 * resources.displayMetrics.density).toInt()
                }
            }
            row.llDots.addView(dot)
        }

        // 汇总文字
        val completed = taken + ignored
        row.tvSummary.text = when {
            totalTimes == 0 -> "无时间点"
            completed == totalTimes && taken == totalTimes -> "全部已吃"
            completed == totalTimes && taken < totalTimes -> "已吃$taken 忽略${ignored}"
            completed == 0 -> "未记录"
            else -> "已吃$taken/$totalTimes"
        }

        // 无障碍
        val statusDesc = buildString {
            append(monthDay)
            if (isToday) append(" 今天")
            append("，")
            for (timeIndex in drug.times.indices) {
                append(drug.times[timeIndex].format())
                append(" ")
                append(when {
                    DrugStore.isTakenOn(this@HistoryActivity, drug.id, timeIndex, dateStr) -> "已吃"
                    DrugStore.isIgnoredOn(this@HistoryActivity, drug.id, timeIndex, dateStr) -> "已忽略"
                    else -> "未记录"
                })
                if (timeIndex < drug.times.size - 1) append("，")
            }
        }
        row.root.contentDescription = statusDesc
    }
}
