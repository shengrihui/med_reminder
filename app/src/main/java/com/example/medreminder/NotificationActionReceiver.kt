package com.example.medreminder

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 处理通知中的“已服用”；“稍后提醒”由 [SnoozeActivity] 处理。 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TAKEN = "com.example.medreminder.ACTION_TAKEN"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val drugId = intent.getIntExtra(ReminderManager.EXTRA_DRUG_ID, -1)
        val scheduleKey = intent.getIntExtra(ReminderManager.EXTRA_SCHEDULE_KEY, -1)
        val dateStr = intent.getStringExtra(ReminderManager.EXTRA_DATE) ?: return
        val time = ReminderTime(
            intent.getIntExtra(ReminderManager.EXTRA_HOUR, -1),
            intent.getIntExtra(ReminderManager.EXTRA_MINUTE, -1)
        )
        if (drugId < 0 || scheduleKey < 0 || time.hour !in 0..23 || time.minute !in 0..59) return

        val drug = DrugStore.getDrug(context, drugId) ?: return
        if (drug.schedules.none { it.scheduleKey == scheduleKey }) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(ReminderManager.notificationId(drugId, scheduleKey, dateStr, time))

        when (intent.action) {
            ACTION_TAKEN -> {
                DrugStore.markTaken(context, drugId, scheduleKey, dateStr)
                ReminderManager.cancelOccurrenceAlarms(context, drugId, scheduleKey, dateStr)
                ReminderManager.cancelOccurrenceNotifications(context, drugId, scheduleKey, dateStr)
            }
        }
    }
}
