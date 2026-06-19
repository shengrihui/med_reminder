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
 * 从 Intent 取 drugId 和 time（hour/minute），检查该时间点今天是否已吃。
 * 没吃 → 发通知 + 注册重复提醒。
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val CHANNEL_ID = "med_reminder_channel_v2"
        private const val OLD_CHANNEL_ID = "med_reminder_channel"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val drugId = intent.getIntExtra(ReminderManager.EXTRA_DRUG_ID, -1)
        val hour = intent.getIntExtra(ReminderManager.EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(ReminderManager.EXTRA_MINUTE, -1)
        val time = ReminderTime(hour, minute)
        Log.d(TAG, "收到闹钟, drugId=$drugId, time=${time.format()}")
        if (drugId < 0 || hour < 0 || minute < 0) return

        val drug = DrugStore.getDrug(context, drugId) ?: return
        if (!drug.enabled) return
        if (!drug.times.any { it.hour == hour && it.minute == minute }) return

        // 已完成（已吃或已忽略）→ 跳过
        if (DrugStore.isCompleted(context, drugId, time)) {
            Log.d(TAG, "[${drug.name} ${time.format()}] 今天已完成，跳过")
            ReminderManager.scheduleDailyAlarm(context, drug, time)
            return
        }

        // 没吃 → 发通知
        showNotification(context, drug, time)

        // 重复提醒
        if (drug.repeatMinutes > 0) {
            ReminderManager.scheduleRepeatAlarm(context, drug, time)
        }

        // 下一次每日闹钟
        ReminderManager.scheduleDailyAlarm(context, drug, time)
    }

    private fun showNotification(context: Context, drug: Drug, time: ReminderTime) {
        createNotificationChannel(context)

        val notifId = ReminderManager.notificationId(drug.id, time)

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
            putExtra(ReminderManager.EXTRA_HOUR, time.hour)
            putExtra(ReminderManager.EXTRA_MINUTE, time.minute)
        }
        val takenPending = PendingIntent.getBroadcast(
            context, notifId * 10 + 1, takenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val laterIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_LATER
            putExtra(ReminderManager.EXTRA_DRUG_ID, drug.id)
            putExtra(ReminderManager.EXTRA_HOUR, time.hour)
            putExtra(ReminderManager.EXTRA_MINUTE, time.minute)
        }
        val laterPending = PendingIntent.getBroadcast(
            context, notifId * 10 + 2, laterIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_med)
            .setContentTitle("用药提醒")
            .setContentText("该用${drug.name}了（${time.format()}），记得用药哦～")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_med, "已用药", takenPending)
            .addAction(R.drawable.ic_med, "稍后提醒", laterPending)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifId, notification)
        Log.d(TAG, "[${drug.name} ${time.format()}] 已发送通知, id=$notifId")
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // 删除旧渠道（旧渠道可能没有声音配置，且创建后无法修改），强制使用新渠道
            try {
                nm.deleteNotificationChannel(OLD_CHANNEL_ID)
            } catch (e: Exception) {
                Log.w(TAG, "删除旧通知渠道失败", e)
            }

            val channel = NotificationChannel(
                CHANNEL_ID, "用药提醒", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "按时用药提醒通知"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 300, 500)
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
            nm.createNotificationChannel(channel)
        }
    }
}
