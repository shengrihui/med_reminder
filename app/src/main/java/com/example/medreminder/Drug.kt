package com.example.medreminder

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
    fun format(): String = String.format("%02d:%02d", hour, minute)

    /** 转成 JSON 字符串 "HH:mm" */
    fun toJson(): String = format()

    companion object {
        fun from(str: String): ReminderTime? {
            return try {
                val parts = str.split(":")
                ReminderTime(parts[0].toInt(), parts[1].toInt())
            } catch (e: Exception) {
                null
            }
        }

        val DEFAULT = ReminderTime(21, 0)
    }
}

/**
 * 药品数据类
 *
 * @param id 唯一ID
 * @param name 药品名称
 * @param times 提醒时间点列表（1~N个）
 * @param intervalDays 服用频率（1=每天，2=隔天...）
 * @param repeatMinutes 未确认时重复提醒间隔（分钟）
 * @param enabled 是否开启提醒
 * @param startDate 下一次（或首次）开始服用的日期，yyyy-MM-dd；仅当 intervalDays > 1 时生效
 */
data class Drug(
    val id: Int,
    val name: String,
    val times: List<ReminderTime>,
    val intervalDays: Int,
    val repeatMinutes: Int,
    val enabled: Boolean,
    val startDate: String = todayDateString()
) {
    /** 判断指定日期是否在该药品的服用计划内 */
    fun isScheduledOn(dateStr: String): Boolean {
        if (intervalDays <= 1) return true
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val start = Calendar.getInstance().apply {
            time = parser.parse(startDate) ?: Date()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val target = Calendar.getInstance().apply {
            time = parser.parse(dateStr) ?: Date()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diffDays = ((target.timeInMillis - start.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
        return diffDays >= 0 && diffDays % intervalDays == 0
    }

    companion object {
        const val DEFAULT_INTERVAL_DAYS = 1
        const val DEFAULT_REPEAT_MINUTES = 10
        const val DEFAULT_NAME = "药"

        fun createDefault(id: Int, name: String = DEFAULT_NAME) = Drug(
            id = id,
            name = name,
            times = listOf(ReminderTime.DEFAULT),
            intervalDays = DEFAULT_INTERVAL_DAYS,
            repeatMinutes = DEFAULT_REPEAT_MINUTES,
            enabled = true,
            startDate = todayDateString()
        )
    }
}
