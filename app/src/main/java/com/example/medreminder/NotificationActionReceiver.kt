package com.example.medreminder

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 通知按钮处理
 * - "已用药"：标记该时间点已用，取消通知和重复提醒
 * - "稍后提醒"：取消当前通知，等重复闹钟自动触发
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotiActionReceiver"
        const val ACTION_TAKEN = "com.example.medreminder.ACTION_TAKEN"
        const val ACTION_LATER = "com.example.medreminder.ACTION_LATER"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val drugId = intent.getIntExtra(ReminderManager.EXTRA_DRUG_ID, -1)
        val hour = intent.getIntExtra(ReminderManager.EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(ReminderManager.EXTRA_MINUTE, -1)
        val time = ReminderTime(hour, minute)
        if (drugId < 0 || hour < 0 || minute < 0) return

        when (intent.action) {
            ACTION_TAKEN -> handleTaken(context, drugId, time)
            ACTION_LATER -> handleLater(context, drugId, time)
        }
    }

    private fun handleTaken(context: Context, drugId: Int, time: ReminderTime) {
        Log.d(TAG, "已用药, drugId=$drugId, time=${time.format()}")
        val drug = DrugStore.getDrug(context, drugId) ?: return
        if (!drug.times.any { it.hour == time.hour && it.minute == time.minute }) return

        DrugStore.markTaken(context, drugId, time)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ReminderManager.notificationId(drugId, time))

        ReminderManager.cancelRepeatAlarm(context, drugId, time)
        ReminderManager.scheduleDailyAlarm(context, drug, time)
    }

    private fun handleLater(context: Context, drugId: Int, time: ReminderTime) {
        Log.d(TAG, "稍后提醒, drugId=$drugId, time=${time.format()}")
        val drug = DrugStore.getDrug(context, drugId) ?: return
        if (!drug.times.any { it.hour == time.hour && it.minute == time.minute }) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ReminderManager.notificationId(drugId, time))

        // 稍后提醒：重新注册一次重复闹钟
        if (drug.repeatMinutes > 0) {
            ReminderManager.scheduleRepeatAlarm(context, drug, time)
        }
    }
}
