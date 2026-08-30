package com.example.medreminder

import java.util.Calendar
import java.util.Locale
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private fun todayDateString(): String =
    String.format(Locale.CHINA, "%04d-%02d-%02d",
        Calendar.getInstance().get(Calendar.YEAR),
        Calendar.getInstance().get(Calendar.MONTH) + 1,
        Calendar.getInstance().get(Calendar.DAY_OF_MONTH))

/**
 * 单个提醒时间点
 *
 * @param hour 时（0-23）
 * @param minute 分（0-59）
 */
data class ReminderTime(val hour: Int, val minute: Int) {
    /** 格式化为 HH:mm */
    fun format(): String = String.format(Locale.CHINA, "%02d:%02d", hour, minute)

    /** 转成 JSON 字符串 "HH:mm" */
    fun toJson(): String = format()

    companion object {
        fun from(str: String): ReminderTime? {
            return try {
                val parts = str.split(":")
                val hour = parts[0].toInt()
                val minute = parts[1].toInt()
                if (hour !in 0..23 || minute !in 0..59) null else ReminderTime(hour, minute)
            } catch (e: Exception) {
                null
            }
        }

        val DEFAULT = ReminderTime(21, 0)
    }
}

/**
 * 一天中的一次服药安排。
 *
 * scheduleKey 是仅用于持久化、闹钟和历史记录关联的稳定键；界面展示“第 N 次服药”，
 * 也可通过 customName 改成“早餐后”等名称。一次服药可以配置多个提醒时间，任一时间确认服药后，
 * 该次服药剩余的提醒都会停止。
 */
data class DoseSchedule(
    val scheduleKey: Int,
    val reminderTimes: List<ReminderTime>,
    val customName: String = ""
) {
    fun displayName(position: Int): String =
        customName.trim().ifEmpty { "第${position + 1}次服药" }
}

/**
 * 药品数据类
 *
 * @param id 唯一ID
 * @param name 药品名称
 * @param schedules 每日服药安排；每次服药可有多个提醒时间点
 * @param intervalDays 服用频率（1=每天，2=隔天...）
 * @param repeatMinutes 未确认时重复提醒间隔（分钟）
 * @param enabled 是否开启提醒
 * @param startDate 下一次（或首次）开始服用的日期，yyyy-MM-dd；仅当 intervalDays > 1 时生效
 */
data class Drug(
    val id: Int,
    val name: String,
    val schedules: List<DoseSchedule>,
    val intervalDays: Int,
    val repeatMinutes: Int,
    val enabled: Boolean,
    val startDate: String = todayDateString()
) {
    /** 判断指定日期是否在该药品的服用计划内 */
    fun isScheduledOn(dateStr: String): Boolean {
        if (intervalDays <= 1) return true
        return try {
            val diffDays = ChronoUnit.DAYS.between(LocalDate.parse(startDate), LocalDate.parse(dateStr))
            diffDays >= 0 && diffDays % intervalDays == 0L
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_DAYS = 1
        const val DEFAULT_REPEAT_MINUTES = 10
        const val DEFAULT_NAME = "药"

        fun createDefault(id: Int, name: String = DEFAULT_NAME) = Drug(
            id = id,
            name = name,
            schedules = listOf(DoseSchedule(1, listOf(ReminderTime.DEFAULT))),
            intervalDays = DEFAULT_INTERVAL_DAYS,
            repeatMinutes = DEFAULT_REPEAT_MINUTES,
            enabled = true,
            startDate = todayDateString()
        )
    }
}
