package com.example.medreminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 闹钟接收器
 *
 * 从 Intent 取 drugId 和 timeIndex，检查该时间点今天是否已吃。
 * 没吃 → 发通知 + 注册重复提醒。
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val CHANNEL_ID = "med_reminder_channel"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val drugId = intent.getIntExtra(ReminderManager.EXTRA_DRUG_ID, -1)
        val timeIndex = intent.getIntExtra(ReminderManager.EXTRA_TIME_INDEX, -1)
        Log.d(TAG, "收到闹钟, drugId=$drugId, timeIndex=$timeIndex")
        if (drugId < 0 || timeIndex < 0) return

        val drug = DrugStore.getDrug(context, drugId) ?: return
        if (!drug.enabled) return
        if (timeIndex >= drug.times.size) return

        // 已吃 → 跳过
        if (DrugStore.isTaken(context, drugId, timeIndex)) {
            Log.d(TAG, "[${drug.name} ${drug.times[timeIndex].format()}] 今天已吃，跳过")
            ReminderManager.scheduleDailyAlarm(context, drug, timeIndex)
            return
        }

        // 没吃 → 发通知
        showNotification(context, drug, timeIndex)

        // 重复提醒
        if (drug.repeatMinutes > 0) {
            ReminderManager.scheduleRepeatAlarm(context, drug, timeIndex)
        }

        // 下一次每日闹钟
        ReminderManager.scheduleDailyAlarm(context, drug, timeIndex)
    }

    private fun showNotification(context: Context, drug: Drug, timeIndex: Int) {
        createNotificationChannel(context)

        val time = drug.times[timeIndex]
        val notifId = ReminderManager.notificationId(drug.id, timeIndex)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context, notifId, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val takenIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_TAKEN
            putExtra(ReminderManager.EXTRA_DRUG_ID, drug.id)
            putExtra(ReminderManager.EXTRA_TIME_INDEX, timeIndex)
        }
        val takenPending = PendingIntent.getBroadcast(
            context, notifId * 10 + 1, takenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val laterIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_LATER
            putExtra(ReminderManager.EXTRA_DRUG_ID, drug.id)
            putExtra(ReminderManager.EXTRA_TIME_INDEX, timeIndex)
        }
        val laterPending = PendingIntent.getBroadcast(
            context, notifId * 10 + 2, laterIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_med)
            .setContentTitle("吃药提醒")
            .setContentText("该吃${drug.name}了（${time.format()}），记得吃药哦～")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_med, "已吃药", takenPending)
            .addAction(R.drawable.ic_med, "稍后提醒", laterPending)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifId, notification)
        Log.d(TAG, "[${drug.name} ${time.format()}] 已发送通知, id=$notifId")
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "吃药提醒", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "按时吃药提醒通知"
                enableVibration(true)
                enableLights(true)
                lightColor = Color.RED
                // 锁屏显示完整内容
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                // 显示桌面角标
                setShowBadge(true)
                // 默认通知铃声
                val soundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                setSound(soundUri, audioAttributes)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
