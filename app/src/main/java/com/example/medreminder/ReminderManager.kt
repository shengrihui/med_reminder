package com.example.medreminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 闹钟管理器
 *
 * 每个药品的每个时间点都有独立的闹钟。
 * 以"小时:分钟"为标识，避免时间点增删导致 requestCode 错位。
 *
 * requestCode 计算规则：
 * - 每日闹钟 = BASE_DAILY + drugId * 10000 + hour * 100 + minute
 * - 重复闹钟 = BASE_REPEAT + drugId * 10000 + hour * 100 + minute
 *
 * 通知 ID = BASE_NOTIFICATION + drugId * 10000 + hour * 100 + minute
 *
 * Intent extras:
 * - EXTRA_DRUG_ID: 药品ID
 * - EXTRA_HOUR: 时间点小时
 * - EXTRA_MINUTE: 时间点分钟
 */
object ReminderManager {

    private const val TAG = "ReminderManager"

    private const val BASE_DAILY = 10_000
    private const val BASE_REPEAT = 20_000
    const val BASE_NOTIFICATION = 8_000

    const val EXTRA_DRUG_ID = "drug_id"
    const val EXTRA_HOUR = "hour"
    const val EXTRA_MINUTE = "minute"

    /* ===================== 闹钟注册 ===================== */

    /** 注册某药品某时间点的每日闹钟 */
    fun scheduleDailyAlarm(context: Context, drug: Drug, time: ReminderTime) {
        if (!drug.enabled) return

        val interval = if (drug.intervalDays < 1) 1 else drug.intervalDays
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, time.hour)
            set(Calendar.MINUTE, time.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (interval == 1) {
            // 每天：从今晚/明早开始
            val today = Calendar.getInstance()
            calendar.set(Calendar.YEAR, today.get(Calendar.YEAR))
            calendar.set(Calendar.MONTH, today.get(Calendar.MONTH))
            calendar.set(Calendar.DAY_OF_MONTH, today.get(Calendar.DAY_OF_MONTH))
            if (calendar.timeInMillis <= now) {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
            }
        } else {
            // 非每天：按 startDate 和间隔计算下一个服用日
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            val todayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val start = Calendar.getInstance().apply {
                setTime(parser.parse(drug.startDate) ?: Date())
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val diffDays = ((todayStart.timeInMillis - start.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
            val offsetDays = if (diffDays <= 0) 0 else ((diffDays + interval - 1) / interval) * interval
            val nextDate = start.clone() as Calendar
            nextDate.add(Calendar.DAY_OF_MONTH, offsetDays)
            calendar.set(Calendar.YEAR, nextDate.get(Calendar.YEAR))
            calendar.set(Calendar.MONTH, nextDate.get(Calendar.MONTH))
            calendar.set(Calendar.DAY_OF_MONTH, nextDate.get(Calendar.DAY_OF_MONTH))
            if (calendar.timeInMillis <= now) {
                calendar.add(Calendar.DAY_OF_MONTH, interval)
            }
        }

        val requestCode = dailyRequestCode(drug.id, time)
        setAlarmClock(context, requestCode, calendar.timeInMillis, drug.id, time)
        Log.d(TAG, "[${drug.name} ${time.format()}] 已注册每日闹钟，下次: ${formatTime(calendar.timeInMillis)}")
    }

    /** 注册某药品所有时间点的每日闹钟 */
    fun scheduleAllDailyAlarms(context: Context, drug: Drug) {
        if (!drug.enabled) return
        for (time in drug.times) {
            scheduleDailyAlarm(context, drug, time)
        }
    }

    /** 注册重复提醒闹钟 */
    fun scheduleRepeatAlarm(context: Context, drug: Drug, time: ReminderTime) {
        val triggerAt = System.currentTimeMillis() + drug.repeatMinutes * 60_000L
        val requestCode = repeatRequestCode(drug.id, time)
        setExactAlarm(context, requestCode, triggerAt, drug.id, time)
        Log.d(TAG, "[${drug.name} ${time.format()}] 已注册重复提醒，${drug.repeatMinutes}分钟后")
    }

    /** 取消某药品某时间点的每日闹钟 */
    fun cancelDailyAlarm(context: Context, drugId: Int, time: ReminderTime) {
        cancelAlarm(context, dailyRequestCode(drugId, time), drugId, time)
    }

    /** 取消某药品某时间点的重复闹钟 */
    fun cancelRepeatAlarm(context: Context, drugId: Int, time: ReminderTime) {
        cancelAlarm(context, repeatRequestCode(drugId, time), drugId, time)
    }

    /** 取消某药品所有闹钟（所有可能的时间点 00:00~23:59） */
    fun cancelAllAlarms(context: Context, drugId: Int) {
        for (hour in 0..23) {
            for (minute in 0..59) {
                val time = ReminderTime(hour, minute)
                cancelAlarm(context, dailyRequestCode(drugId, time), drugId, time)
                cancelAlarm(context, repeatRequestCode(drugId, time), drugId, time)
            }
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

    private fun dailyRequestCode(drugId: Int, time: ReminderTime) =
        BASE_DAILY + drugId * 10000 + time.hour * 100 + time.minute

    private fun repeatRequestCode(drugId: Int, time: ReminderTime) =
        BASE_REPEAT + drugId * 10000 + time.hour * 100 + time.minute

    fun notificationId(drugId: Int, time: ReminderTime) =
        BASE_NOTIFICATION + drugId * 10000 + time.hour * 100 + time.minute

    /* ===================== 闹钟底层 ===================== */

    private fun setExactAlarm(
        context: Context,
        requestCode: Int,
        triggerAtMillis: Long,
        drugId: Int,
        time: ReminderTime
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_DRUG_ID, drugId)
            putExtra(EXTRA_HOUR, time.hour)
            putExtra(EXTRA_MINUTE, time.minute)
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

    /**
     * 使用 AlarmClock 注册每日提醒。
     * AlarmClock 会强制唤醒设备并绕过 Doze/应用待机限制，是用药提醒最可靠的方式。
     */
    private fun setAlarmClock(
        context: Context,
        requestCode: Int,
        triggerAtMillis: Long,
        drugId: Int,
        time: ReminderTime
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_DRUG_ID, drugId)
            putExtra(EXTRA_HOUR, time.hour)
            putExtra(EXTRA_MINUTE, time.minute)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        try {
            val info = AlarmManager.AlarmClockInfo(triggerAtMillis, null)
            alarmManager.setAlarmClock(info, pendingIntent)
        } catch (e: SecurityException) {
            Log.w(TAG, "setAlarmClock 失败，降级到 setExactAndAllowWhileIdle", e)
            setExactAlarm(context, requestCode, triggerAtMillis, drugId, time)
        }
    }

    private fun cancelAlarm(context: Context, requestCode: Int, drugId: Int, time: ReminderTime) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_DRUG_ID, drugId)
            putExtra(EXTRA_HOUR, time.hour)
            putExtra(EXTRA_MINUTE, time.minute)
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
