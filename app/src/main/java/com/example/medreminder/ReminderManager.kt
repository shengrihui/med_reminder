package com.example.medreminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar
import java.util.Locale

/**
 * 闹钟管理器
 *
 * 每个药品的每个时间点都有独立的闹钟。
 *
 * requestCode 计算规则：
 * - 每日闹钟 = BASE_DAILY + drugId * 100 + timeIndex
 * - 重复闹钟 = BASE_REPEAT + drugId * 100 + timeIndex
 *
 * 通知 ID = BASE_NOTIFICATION + drugId * 100 + timeIndex
 *
 * Intent extras:
 * - EXTRA_DRUG_ID: 药品ID
 * - EXTRA_TIME_INDEX: 时间点索引
 */
object ReminderManager {

    private const val TAG = "ReminderManager"

    private const val BASE_DAILY = 10_000
    private const val BASE_REPEAT = 20_000
    const val BASE_NOTIFICATION = 8_000

    const val EXTRA_DRUG_ID = "drug_id"
    const val EXTRA_TIME_INDEX = "time_index"

    /* ===================== 闹钟注册 ===================== */

    /** 注册某药品某时间点的每日闹钟 */
    fun scheduleDailyAlarm(context: Context, drug: Drug, timeIndex: Int) {
        if (!drug.enabled) return
        if (timeIndex < 0 || timeIndex >= drug.times.size) return

        val time = drug.times[timeIndex]
        val interval = if (drug.intervalDays < 1) 1 else drug.intervalDays
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, time.hour)
            set(Calendar.MINUTE, time.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_MONTH, interval)
            }
        }

        setExactAlarm(context, dailyRequestCode(drug.id, timeIndex), calendar.timeInMillis, drug.id, timeIndex)
        Log.d(TAG, "[${drug.name} ${time.format()}] 已注册每日闹钟，下次: ${formatTime(calendar.timeInMillis)}")
    }

    /** 注册某药品所有时间点的每日闹钟 */
    fun scheduleAllDailyAlarms(context: Context, drug: Drug) {
        if (!drug.enabled) return
        for (i in drug.times.indices) {
            scheduleDailyAlarm(context, drug, i)
        }
    }

    /** 注册重复提醒闹钟 */
    fun scheduleRepeatAlarm(context: Context, drug: Drug, timeIndex: Int) {
        val triggerAt = System.currentTimeMillis() + drug.repeatMinutes * 60_000L
        setExactAlarm(context, repeatRequestCode(drug.id, timeIndex), triggerAt, drug.id, timeIndex)
        Log.d(TAG, "[${drug.name}] 已注册重复提醒，${drug.repeatMinutes}分钟后")
    }

    /** 取消某药品某时间点的每日闹钟 */
    fun cancelDailyAlarm(context: Context, drugId: Int, timeIndex: Int) {
        cancelAlarm(context, dailyRequestCode(drugId, timeIndex), drugId, timeIndex)
    }

    /** 取消某药品某时间点的重复闹钟 */
    fun cancelRepeatAlarm(context: Context, drugId: Int, timeIndex: Int) {
        cancelAlarm(context, repeatRequestCode(drugId, timeIndex), drugId, timeIndex)
    }

    /** 取消某药品所有闹钟（所有时间点） */
    fun cancelAllAlarms(context: Context, drugId: Int) {
        // 最多遍历 100 个时间点（实际不会有这么多）
        for (i in 0 until 100) {
            cancelAlarm(context, dailyRequestCode(drugId, i), drugId, i)
            cancelAlarm(context, repeatRequestCode(drugId, i), drugId, i)
        }
    }

    /** 注册所有已开启药品的闹钟（开机自启用） */
    fun scheduleAllEnabledAlarms(context: Context) {
        for (drug in DrugStore.getAllDrugs(context)) {
            if (drug.enabled) {
                scheduleAllDailyAlarms(context, drug)
            }
        }
    }

    /* ===================== requestCode 计算 ===================== */

    private fun dailyRequestCode(drugId: Int, timeIndex: Int) = BASE_DAILY + drugId * 100 + timeIndex
    private fun repeatRequestCode(drugId: Int, timeIndex: Int) = BASE_REPEAT + drugId * 100 + timeIndex
    fun notificationId(drugId: Int, timeIndex: Int) = BASE_NOTIFICATION + drugId * 100 + timeIndex

    /* ===================== 闹钟底层 ===================== */

    private fun setExactAlarm(context: Context, requestCode: Int, triggerAtMillis: Long, drugId: Int, timeIndex: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_DRUG_ID, drugId)
            putExtra(EXTRA_TIME_INDEX, timeIndex)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                Log.w(TAG, "没有精确闹钟权限，使用 setAndAllowWhileIdle")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun cancelAlarm(context: Context, requestCode: Int, drugId: Int, timeIndex: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_DRUG_ID, drugId)
            putExtra(EXTRA_TIME_INDEX, timeIndex)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        pendingIntent?.let { alarmManager.cancel(it) }
    }

    private fun formatTime(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return String.format(Locale.CHINA, "%04d-%02d-%02d %02d:%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }
}
