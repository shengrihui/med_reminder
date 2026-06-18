package com.example.medreminder

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 通知按钮处理
 * - "已吃药"：标记该时间点已吃，取消通知和重复提醒
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
        val timeIndex = intent.getIntExtra(ReminderManager.EXTRA_TIME_INDEX, -1)
        if (drugId < 0 || timeIndex < 0) return

        when (intent.action) {
            ACTION_TAKEN -> handleTaken(context, drugId, timeIndex)
            ACTION_LATER -> handleLater(context, drugId, timeIndex)
        }
    }

    private fun handleTaken(context: Context, drugId: Int, timeIndex: Int) {
        Log.d(TAG, "已吃药, drugId=$drugId, timeIndex=$timeIndex")
        DrugStore.markTaken(context, drugId, timeIndex)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ReminderManager.notificationId(drugId, timeIndex))

        ReminderManager.cancelRepeatAlarm(context, drugId, timeIndex)

        DrugStore.getDrug(context, drugId)?.let { drug ->
            ReminderManager.scheduleDailyAlarm(context, drug, timeIndex)
        }
    }

    private fun handleLater(context: Context, drugId: Int, timeIndex: Int) {
        Log.d(TAG, "稍后提醒, drugId=$drugId, timeIndex=$timeIndex")
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ReminderManager.notificationId(drugId, timeIndex))
    }
}
