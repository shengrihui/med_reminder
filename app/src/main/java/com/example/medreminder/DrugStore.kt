package com.example.medreminder

import android.content.Context
import android.content.SharedPreferences.Editor
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * 药品存储管理器
 *
 * 用 SharedPreferences + JSON 存储多个药品。
 * 历史记录按 "drugId|计划时间HH:mm|日期yyyy-MM-dd|实际操作时间HH:mm" 格式存储，
 * 以"时间"为标识，避免时间点增删导致错位；同时记录实际操作时间，用于历史记录显示和排序。
 *
 * 两种"完成"状态：
 * - taken：已吃药（历史记录显示绿色）
 * - ignored：已忽略（不再提醒，历史记录显示黄色，不算已吃）
 *
 * 首页过滤逻辑：某时间点 taken 或 ignored 都算"已完成"，不再显示。
 */
object DrugStore {

    private const val PREFS_NAME = "med_reminder_prefs"
    private const val KEY_DRUGS = "drugs_json"
    private const val KEY_NEXT_ID = "next_drug_id"
    private const val KEY_TAKEN = "taken_records"    // Set<String>，格式 "drugId|计划HH:mm|date|实际HH:mm"
    private const val KEY_IGNORED = "ignored_records" // Set<String>，格式同上

    /* ===================== 药品 CRUD ===================== */

    fun getAllDrugs(context: Context): List<Drug> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DRUGS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val timesArr = obj.getJSONArray("times")
                val times = (0 until timesArr.length()).map { j ->
                    ReminderTime.from(timesArr.getString(j)) ?: ReminderTime.DEFAULT
                }
                Drug(
                    id = obj.getInt("id"),
                    name = obj.getString("name"),
                    times = times,
                    intervalDays = obj.getInt("intervalDays"),
                    repeatMinutes = obj.getInt("repeatMinutes"),
                    enabled = obj.getBoolean("enabled"),
                    startDate = obj.optString("startDate", dateString(Calendar.getInstance()))
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getDrug(context: Context, drugId: Int): Drug? =
        getAllDrugs(context).find { it.id == drugId }

    fun saveDrug(context: Context, drug: Drug) {
        val list = getAllDrugs(context).toMutableList()
        val idx = list.indexOfFirst { it.id == drug.id }
        if (idx >= 0) list[idx] = drug else list.add(drug)
        persist(context, list)
    }

    /**
     * 添加药品。如果同名药品已存在（忽略首尾空格），返回 null。
     * 调用方应先调用 isDrugNameDuplicate 检查并提示用户。
     */
    fun addDrug(context: Context, name: String): Drug? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return null
        if (isDrugNameDuplicate(context, trimmedName, excludeId = -1)) return null

        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val newId = sp.getInt(KEY_NEXT_ID, 1)
        sp.edit().putInt(KEY_NEXT_ID, newId + 1).commit()

        val drug = Drug.createDefault(id = newId, name = trimmedName)
        val list = getAllDrugs(context).toMutableList()
        list.add(drug)
        persist(context, list)
        return drug
    }

    /**
     * 创建药品（完整配置一次性写入）。用于"新建药品"流程：编辑页保存时才创建。
     * 如果同名药品已存在，返回 null。
     */
    fun createDrug(
        context: Context,
        name: String,
        times: List<ReminderTime>,
        intervalDays: Int,
        repeatMinutes: Int,
        startDate: String
    ): Drug? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return null
        if (times.isEmpty()) return null
        if (isDrugNameDuplicate(context, trimmedName, excludeId = -1)) return null

        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val newId = sp.getInt(KEY_NEXT_ID, 1)
        sp.edit().putInt(KEY_NEXT_ID, newId + 1).commit()

        val drug = Drug(
            id = newId,
            name = trimmedName,
            times = times,
            intervalDays = intervalDays,
            repeatMinutes = repeatMinutes,
            enabled = true,
            startDate = startDate
        )
        val list = getAllDrugs(context).toMutableList()
        list.add(drug)
        persist(context, list)
        return drug
    }

    /** 检查药品名称是否重复（忽略首尾空格、大小写）。excludeId 用于编辑时排除自己。 */
    fun isDrugNameDuplicate(context: Context, name: String, excludeId: Int): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        return getAllDrugs(context).any { it.id != excludeId && it.name.trim() == trimmed }
    }

    fun deleteDrug(context: Context, drugId: Int) {
        // 清理该药品的历史记录（taken + ignored）
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val taken = sp.getStringSet(KEY_TAKEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        taken.removeAll { it.startsWith("$drugId|") }
        val ignored = sp.getStringSet(KEY_IGNORED, emptySet())?.toMutableSet() ?: mutableSetOf()
        ignored.removeAll { it.startsWith("$drugId|") }
        sp.edit()
            .putStringSet(KEY_TAKEN, taken)
            .putStringSet(KEY_IGNORED, ignored)
            .commit()

        val list = getAllDrugs(context).filter { it.id != drugId }
        persist(context, list)
    }

    private fun persist(context: Context, list: List<Drug>) {
        val arr = JSONArray()
        for (d in list) {
            val timesArr = JSONArray()
            for (t in d.times) timesArr.put(t.toJson())
            arr.put(JSONObject().apply {
                put("id", d.id)
                put("name", d.name)
                put("times", timesArr)
                put("intervalDays", d.intervalDays)
                put("repeatMinutes", d.repeatMinutes)
                put("enabled", d.enabled)
                put("startDate", d.startDate)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DRUGS, arr.toString()).commit()
    }

    /* ===================== 历史记录 ===================== */

    /**
     * 一条历史记录
     *
     * @param drugId 药品ID
     * @param drugName 药品名称
     * @param scheduledTime 设定的提醒时间
     * @param dateStr 日期 yyyy-MM-dd
     * @param actualTimeStr 实际操作时间 HH:mm
     * @param isTaken true=已吃，false=已忽略
     */
    data class HistoryRecord(
        val drugId: Int,
        val drugName: String,
        val scheduledTime: ReminderTime,
        val dateStr: String,
        val actualTimeStr: String,
        val isTaken: Boolean
    )

    private const val KEY_RECORDS_MIGRATED = "records_migrated_v2"

    private fun ensureRecordsMigrated(context: Context) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY_RECORDS_MIGRATED, false)) return
        migrateSet(context, KEY_TAKEN)
        migrateSet(context, KEY_IGNORED)
        sp.edit().putBoolean(KEY_RECORDS_MIGRATED, true).commit()
    }

    private fun migrateSet(context: Context, key: String) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = sp.getStringSet(key, emptySet())?.toMutableSet() ?: return
        val migrated = set.map { rec ->
            // 旧格式：drugId|HH:mm|date；新格式：drugId|HH:mm|date|actualTime
            if (rec.count { it == '|' } == 2) "$rec|${rec.split("|")[1]}" else rec
        }.toMutableSet()
        sp.edit().putStringSet(key, migrated).commit()
    }

    private fun getRecordsSet(context: Context, key: String): Set<String> {
        ensureRecordsMigrated(context)
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(key, emptySet())?.toSet() ?: emptySet()
    }

    private fun editRecords(
        context: Context,
        block: (Editor, MutableSet<String>, MutableSet<String>) -> Unit
    ) {
        ensureRecordsMigrated(context)
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val taken = sp.getStringSet(KEY_TAKEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        val ignored = sp.getStringSet(KEY_IGNORED, emptySet())?.toMutableSet() ?: mutableSetOf()
        val editor = sp.edit()
        block(editor, taken, ignored)
        editor.putStringSet(KEY_TAKEN, taken)
            .putStringSet(KEY_IGNORED, ignored)
            .commit()
    }

    private fun recordPrefix(drugId: Int, time: ReminderTime, dateStr: String) =
        "$drugId|${time.format()}|$dateStr|"

    private fun oldRecordExact(drugId: Int, time: ReminderTime, dateStr: String) =
        "$drugId|${time.format()}|$dateStr"

    private fun matchesRecord(rec: String, drugId: Int, time: ReminderTime, dateStr: String): Boolean {
        return rec == oldRecordExact(drugId, time, dateStr)
                || rec.startsWith(recordPrefix(drugId, time, dateStr))
    }

    private fun removeMatchingRecords(
        set: MutableSet<String>,
        drugId: Int,
        time: ReminderTime,
        dateStr: String
    ) {
        val exact = oldRecordExact(drugId, time, dateStr)
        val prefix = recordPrefix(drugId, time, dateStr)
        set.removeAll { it == exact || it.startsWith(prefix) }
    }

    private fun currentTimeStr(): String {
        val now = Calendar.getInstance()
        return String.format(Locale.CHINA, "%02d:%02d:%02d",
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND))
    }

    /** 标记某药品某时间点今天已吃，同时记录实际操作时间 */
    fun markTaken(context: Context, drugId: Int, time: ReminderTime) {
        val dateStr = todayString()
        editRecords(context) { _, taken, ignored ->
            removeMatchingRecords(taken, drugId, time, dateStr)
            removeMatchingRecords(ignored, drugId, time, dateStr)
            taken.add("$drugId|${time.format()}|$dateStr|${currentTimeStr()}")
        }
    }

    /** 标记某药品某时间点今天已忽略（不再提醒，但不算已吃） */
    fun markIgnored(context: Context, drugId: Int, time: ReminderTime) {
        val dateStr = todayString()
        editRecords(context) { _, taken, ignored ->
            removeMatchingRecords(taken, drugId, time, dateStr)
            removeMatchingRecords(ignored, drugId, time, dateStr)
            ignored.add("$drugId|${time.format()}|$dateStr|${currentTimeStr()}")
        }
    }

    /** 撤销某药品某时间点今天的完成状态（同时清除 taken 和 ignored） */
    fun undoCompleted(context: Context, drugId: Int, time: ReminderTime) {
        val dateStr = todayString()
        editRecords(context) { _, taken, ignored ->
            removeMatchingRecords(taken, drugId, time, dateStr)
            removeMatchingRecords(ignored, drugId, time, dateStr)
        }
    }

    /** 删除指定日期某药品某时间点的历史记录（taken + ignored 均清除） */
    fun deleteRecord(context: Context, drugId: Int, time: ReminderTime, dateStr: String) {
        editRecords(context) { _, taken, ignored ->
            removeMatchingRecords(taken, drugId, time, dateStr)
            removeMatchingRecords(ignored, drugId, time, dateStr)
        }
    }

    /** 清空某药品的全部历史记录（taken + ignored） */
    fun deleteAllRecordsForDrug(context: Context, drugId: Int) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val taken = sp.getStringSet(KEY_TAKEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        taken.removeAll { it.startsWith("$drugId|") }
        val ignored = sp.getStringSet(KEY_IGNORED, emptySet())?.toMutableSet() ?: mutableSetOf()
        ignored.removeAll { it.startsWith("$drugId|") }
        sp.edit()
            .putStringSet(KEY_TAKEN, taken)
            .putStringSet(KEY_IGNORED, ignored)
            .commit()
    }

    /** 某药品某时间点今天是否已吃 */
    fun isTaken(context: Context, drugId: Int, time: ReminderTime): Boolean {
        val taken = getRecordsSet(context, KEY_TAKEN)
        return taken.any { matchesRecord(it, drugId, time, todayString()) }
    }

    /** 某药品某时间点今天是否已忽略 */
    fun isIgnored(context: Context, drugId: Int, time: ReminderTime): Boolean {
        val ignored = getRecordsSet(context, KEY_IGNORED)
        return ignored.any { matchesRecord(it, drugId, time, todayString()) }
    }

    /** 某药品某时间点今天是否已完成（已吃或已忽略） */
    fun isCompleted(context: Context, drugId: Int, time: ReminderTime): Boolean {
        return isTaken(context, drugId, time) || isIgnored(context, drugId, time)
    }

    /** 某药品某时间点指定日期是否已吃 */
    fun isTakenOn(context: Context, drugId: Int, time: ReminderTime, dateStr: String): Boolean {
        val taken = getRecordsSet(context, KEY_TAKEN)
        return taken.any { matchesRecord(it, drugId, time, dateStr) }
    }

    /** 某药品某时间点指定日期是否已忽略 */
    fun isIgnoredOn(context: Context, drugId: Int, time: ReminderTime, dateStr: String): Boolean {
        val ignored = getRecordsSet(context, KEY_IGNORED)
        return ignored.any { matchesRecord(it, drugId, time, dateStr) }
    }

    /** 某药品某时间点指定日期是否已完成（已吃或已忽略） */
    fun isCompletedOn(context: Context, drugId: Int, time: ReminderTime, dateStr: String): Boolean {
        return isTakenOn(context, drugId, time, dateStr) || isIgnoredOn(context, drugId, time, dateStr)
    }

    /** 某药品今天是否所有时间点都已完成（已吃或已忽略） */
    fun isAllCompleted(context: Context, drug: Drug): Boolean {
        if (drug.times.isEmpty()) return true
        return drug.times.all { isCompleted(context, drug.id, it) }
    }

    /** 某药品今天还有几个时间点未完成 */
    fun remainingCount(context: Context, drug: Drug): Int {
        if (drug.times.isEmpty()) return 0
        return drug.times.count { !isCompleted(context, drug.id, it) }
    }

    /** 获取所有历史记录，按实际操作时间倒序排列（最新在前） */
    fun getHistoryRecords(context: Context): List<HistoryRecord> {
        val drugs = getAllDrugs(context).associateBy { it.id }
        val taken = getRecordsSet(context, KEY_TAKEN)
        val ignored = getRecordsSet(context, KEY_IGNORED)
        val list = mutableListOf<HistoryRecord>()
        taken.mapNotNullTo(list) { parseRecord(it, true, drugs) }
        ignored.mapNotNullTo(list) { parseRecord(it, false, drugs) }
        return list.sortedByDescending { recordTimestamp(it) }
    }

    private fun parseRecord(str: String, isTaken: Boolean, drugs: Map<Int, Drug>): HistoryRecord? {
        val parts = str.split("|")
        if (parts.size < 3) return null
        val drugId = parts[0].toIntOrNull() ?: return null
        val scheduledTime = ReminderTime.from(parts[1]) ?: return null
        val dateStr = parts[2]
        val actualTimeStr = if (parts.size >= 4) parts[3] else scheduledTime.format()
        val drug = drugs[drugId] ?: return null
        return HistoryRecord(drugId, drug.name, scheduledTime, dateStr, actualTimeStr, isTaken)
    }

    private fun recordTimestamp(record: HistoryRecord): Long {
        return try {
            val dateParts = record.dateStr.split("-").map { it.toInt() }
            val timeParts = record.actualTimeStr.split(":").map { it.toInt() }
            val second = if (timeParts.size >= 3) timeParts[2] else 0
            Calendar.getInstance().apply {
                set(dateParts[0], dateParts[1] - 1, dateParts[2],
                    timeParts[0], timeParts[1], second)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } catch (e: Exception) {
            0L
        }
    }

    /* ===================== 工具 ===================== */

    private fun todayString(): String = dateString(Calendar.getInstance())

    fun dateString(calendar: Calendar): String =
        String.format(Locale.CHINA, "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH))
}
