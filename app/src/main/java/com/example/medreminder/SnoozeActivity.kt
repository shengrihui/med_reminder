package com.example.medreminder

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** 从通知打开，为当前这一条提醒选择稍后多久再提醒。 */
class SnoozeActivity : AppCompatActivity() {

    private var drugId = -1
    private var scheduleKey = -1
    private var dateStr = ""
    private var sourceTime = ReminderTime(-1, -1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drugId = intent.getIntExtra(ReminderManager.EXTRA_DRUG_ID, -1)
        scheduleKey = intent.getIntExtra(ReminderManager.EXTRA_SCHEDULE_KEY, -1)
        dateStr = intent.getStringExtra(ReminderManager.EXTRA_DATE).orEmpty()
        sourceTime = ReminderTime(
            intent.getIntExtra(ReminderManager.EXTRA_HOUR, -1),
            intent.getIntExtra(ReminderManager.EXTRA_MINUTE, -1)
        )
        if (!isRequestValid()) {
            finish()
            return
        }
        showChoices()
    }

    private fun isRequestValid(): Boolean {
        if (drugId < 0 || scheduleKey < 0 || dateStr != DrugStore.todayString()) return false
        if (sourceTime.hour !in 0..23 || sourceTime.minute !in 0..59) return false
        val drug = DrugStore.getDrug(this, drugId) ?: return false
        val schedule = drug.schedules.find { it.scheduleKey == scheduleKey } ?: return false
        return sourceTime in schedule.reminderTimes &&
            !DrugStore.isCompletedOn(this, drugId, scheduleKey, dateStr)
    }

    private fun showChoices() {
        val drug = DrugStore.getDrug(this, drugId) ?: return finish()
        val scheduleIndex = drug.schedules.indexOfFirst { it.scheduleKey == scheduleKey }
        val label = drug.schedules.getOrNull(scheduleIndex)?.displayName(scheduleIndex) ?: "本次服药"
        val choices = arrayOf("10分钟后", "30分钟后", "1小时后", "自定义…")
        AlertDialog.Builder(this)
            .setTitle("稍后提醒·${drug.name}$label")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> applySnooze(10)
                    1 -> applySnooze(30)
                    2 -> applySnooze(60)
                    else -> showCustomDelay()
                }
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showCustomDelay() {
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            hint = "输入分钟数"
            contentDescription = "稍后提醒的分钟数"
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding((24 * density).toInt(), 0, (24 * density).toInt(), 0)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("自定义稍后提醒")
            .setMessage("请输入1到720分钟")
            .setView(input)
            .setPositiveButton("确定", null)
            .setNegativeButton("返回") { _, _ -> showChoices() }
            .setOnCancelListener { showChoices() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutes = input.text.toString().toIntOrNull()
                if (minutes == null || minutes !in 1..720) {
                    input.error = "请输入1到720之间的数字"
                } else {
                    dialog.dismiss()
                    applySnooze(minutes)
                }
            }
        }
        dialog.show()
    }

    private fun applySnooze(minutes: Int) {
        val drug = DrugStore.getDrug(this, drugId) ?: return finish()
        val schedule = drug.schedules.find { it.scheduleKey == scheduleKey } ?: return finish()
        val scheduled = ReminderManager.scheduleRepeatAlarm(
            this, drug, schedule, dateStr, sourceTime, delayMinutes = minutes
        )
        if (scheduled) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(ReminderManager.notificationId(drugId, scheduleKey, dateStr, sourceTime))
            Toast.makeText(this, "将在${formatDelay(minutes)}后再次提醒", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "选定时间已跨过今天，未修改提醒", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun formatDelay(minutes: Int): String = when {
        minutes < 60 -> "${minutes}分钟"
        minutes % 60 == 0 -> "${minutes / 60}小时"
        else -> "${minutes / 60}小时${minutes % 60}分钟"
    }

    companion object {
        const val ACTION_CHOOSE_SNOOZE = "com.example.medreminder.ACTION_CHOOSE_SNOOZE"
    }
}
