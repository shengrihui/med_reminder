package com.example.medreminder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 药品、服药任务状态和历史记录的本地存储。 */
object DrugStore {

    private const val PREFS_NAME = "med_reminder_prefs"
    private const val KEY_DRUGS = "drugs_json"
    private const val KEY_NEXT_ID = "next_drug_id"
    private const val KEY_LEGACY_TAKEN = "taken_records"
    private const val KEY_LEGACY_IGNORED = "ignored_records"

    // v3 记录：drugId|scheduleKey|date|actualTime|plannedTimes|scheduleLabel
    private const val KEY_TAKEN = "dose_taken_records_v3"
    private const val KEY_SKIPPED = "dose_skipped_records_v3"
    private const val KEY_MISSED = "dose_missed_records_v3"
    private const val KEY_RECORDS_MIGRATED = "dose_records_migrated_v3"
    private const val KEY_LAST_RECONCILED_DATE = "last_reconciled_date_v3"

    enum class HistoryStatus { TAKEN, SKIPPED, MISSED }

    data class HistoryRecord(
        val drugId: Int,
        val drugName: String,
        val scheduleKey: Int,
        val scheduleLabel: String,
        val plannedTimes: List<ReminderTime>,
        val dateStr: String,
        val actualTimeStr: String,
        val status: HistoryStatus
    )

    /* ===================== 药品 CRUD ===================== */

    fun getAllDrugs(context: Context): List<Drug> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DRUGS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { parseDrug(array.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseDrug(obj: JSONObject): Drug {
        val schedulesJson = obj.optJSONArray("schedules")
        val schedules = if (schedulesJson != null) {
            (0 until schedulesJson.length()).map { index ->
                val scheduleObj = schedulesJson.getJSONObject(index)
                DoseSchedule(
                    scheduleKey = scheduleObj.optInt("scheduleKey", index + 1),
                    reminderTimes = parseTimes(scheduleObj.getJSONArray("reminderTimes")),
                    customName = scheduleObj.optString("customName", "")
                )
            }.filter { it.reminderTimes.isNotEmpty() }
        } else {
            // 旧数据无法推断哪些时间属于同一次服药，因此每个旧时间安全地迁移为独立安排。
            parseTimes(obj.optJSONArray("times") ?: JSONArray()).mapIndexed { index, time ->
                DoseSchedule(index + 1, listOf(time))
            }
        }

        return Drug(
            id = obj.getInt("id"),
            name = obj.getString("name"),
            schedules = schedules.ifEmpty { listOf(DoseSchedule(1, listOf(ReminderTime.DEFAULT))) },
            intervalDays = obj.optInt("intervalDays", Drug.DEFAULT_INTERVAL_DAYS),
            repeatMinutes = obj.optInt("repeatMinutes", Drug.DEFAULT_REPEAT_MINUTES),
            enabled = obj.optBoolean("enabled", true),
            startDate = obj.optString("startDate", todayString())
        )
    }

    private fun parseTimes(array: JSONArray): List<ReminderTime> =
        (0 until array.length()).mapNotNull { ReminderTime.from(array.optString(it)) }

    fun getDrug(context: Context, drugId: Int): Drug? =
        getAllDrugs(context).find { it.id == drugId }

    fun saveDrug(context: Context, drug: Drug) {
        val list = getAllDrugs(context).toMutableList()
        val index = list.indexOfFirst { it.id == drug.id }
        if (index >= 0) list[index] = drug else list.add(drug)
        persist(context, list)
    }

    fun createDrug(
        context: Context,
        name: String,
        schedules: List<DoseSchedule>,
        intervalDays: Int,
        repeatMinutes: Int,
        startDate: String
    ): Drug? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty() || schedules.isEmpty() || schedules.any { it.reminderTimes.isEmpty() }) return null
        if (isDrugNameDuplicate(context, trimmedName, excludeId = -1)) return null

        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val newId = preferences.getInt(KEY_NEXT_ID, 1)
        preferences.edit().putInt(KEY_NEXT_ID, newId + 1).commit()
        val drug = Drug(
            id = newId,
            name = trimmedName,
            schedules = schedules,
            intervalDays = intervalDays,
            repeatMinutes = repeatMinutes,
            enabled = true,
            startDate = startDate
        )
        persist(context, getAllDrugs(context) + drug)
        return drug
    }

    fun isDrugNameDuplicate(context: Context, name: String, excludeId: Int): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() && getAllDrugs(context).any {
            it.id != excludeId && it.name.trim().equals(trimmed, ignoreCase = true)
        }
    }

    fun deleteDrug(context: Context, drugId: Int) {
        deleteAllRecordsForDrug(context, drugId)
        persist(context, getAllDrugs(context).filter { it.id != drugId })
    }

    private fun persist(context: Context, drugs: List<Drug>) {
        val array = JSONArray()
        drugs.forEach { drug ->
            val schedulesJson = JSONArray()
            drug.schedules.forEach { schedule ->
                schedulesJson.put(JSONObject().apply {
                    put("scheduleKey", schedule.scheduleKey)
                    put("customName", schedule.customName.trim())
                    put("reminderTimes", JSONArray().apply {
                        schedule.reminderTimes.forEach { put(it.toJson()) }
                    })
                })
            }
            array.put(JSONObject().apply {
                put("id", drug.id)
                put("name", drug.name)
                put("schedules", schedulesJson)
                put("intervalDays", drug.intervalDays)
                put("repeatMinutes", drug.repeatMinutes)
                put("enabled", drug.enabled)
                put("startDate", drug.startDate)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DRUGS, array.toString()).commit()
    }

    /* ===================== 服药任务状态 ===================== */

    fun markTaken(context: Context, drugId: Int, scheduleKey: Int, dateStr: String = todayString()) =
        setStatus(context, drugId, scheduleKey, dateStr, HistoryStatus.TAKEN)

    fun markSkipped(context: Context, drugId: Int, scheduleKey: Int, dateStr: String = todayString()) =
        setStatus(context, drugId, scheduleKey, dateStr, HistoryStatus.SKIPPED)

    fun markMissed(context: Context, drugId: Int, scheduleKey: Int, dateStr: String): Boolean {
        if (isCompletedOn(context, drugId, scheduleKey, dateStr)) return false
        setStatus(context, drugId, scheduleKey, dateStr, HistoryStatus.MISSED)
        return true
    }

    private fun setStatus(
        context: Context,
        drugId: Int,
        scheduleKey: Int,
        dateStr: String,
        status: HistoryStatus
    ) {
        val drug = getDrug(context, drugId) ?: return
        val scheduleIndex = drug.schedules.indexOfFirst { it.scheduleKey == scheduleKey }
        val schedule = drug.schedules.getOrNull(scheduleIndex) ?: return
        val actualTime = if (status == HistoryStatus.MISSED) "--:--:--" else currentTimeString()
        val value = listOf(
            drugId.toString(), scheduleKey.toString(), dateStr, actualTime,
            schedule.reminderTimes.joinToString(",") { it.format() }, schedule.displayName(scheduleIndex)
        ).joinToString("|")

        editRecordSets(context) { taken, skipped, missed ->
            removeOccurrence(taken, drugId, scheduleKey, dateStr)
            removeOccurrence(skipped, drugId, scheduleKey, dateStr)
            removeOccurrence(missed, drugId, scheduleKey, dateStr)
            when (status) {
                HistoryStatus.TAKEN -> taken.add(value)
                HistoryStatus.SKIPPED -> skipped.add(value)
                HistoryStatus.MISSED -> missed.add(value)
            }
        }
    }

    fun undoCompleted(context: Context, drugId: Int, scheduleKey: Int, dateStr: String = todayString()) {
        editRecordSets(context) { taken, skipped, missed ->
            removeOccurrence(taken, drugId, scheduleKey, dateStr)
            removeOccurrence(skipped, drugId, scheduleKey, dateStr)
            removeOccurrence(missed, drugId, scheduleKey, dateStr)
        }
    }

    fun deleteRecord(context: Context, drugId: Int, scheduleKey: Int, dateStr: String) =
        undoCompleted(context, drugId, scheduleKey, dateStr)

    fun deleteAllRecordsForDrug(context: Context, drugId: Int) {
        editRecordSets(context) { taken, skipped, missed ->
            taken.removeAll { it.startsWith("$drugId|") }
            skipped.removeAll { it.startsWith("$drugId|") }
            missed.removeAll { it.startsWith("$drugId|") }
        }
    }

    fun isTakenOn(context: Context, drugId: Int, scheduleKey: Int, dateStr: String): Boolean =
        containsOccurrence(getRecordSet(context, KEY_TAKEN), drugId, scheduleKey, dateStr)

    fun isSkippedOn(context: Context, drugId: Int, scheduleKey: Int, dateStr: String): Boolean =
        containsOccurrence(getRecordSet(context, KEY_SKIPPED), drugId, scheduleKey, dateStr)

    fun isMissedOn(context: Context, drugId: Int, scheduleKey: Int, dateStr: String): Boolean =
        containsOccurrence(getRecordSet(context, KEY_MISSED), drugId, scheduleKey, dateStr)

    fun isCompletedOn(context: Context, drugId: Int, scheduleKey: Int, dateStr: String): Boolean =
        isTakenOn(context, drugId, scheduleKey, dateStr) ||
            isSkippedOn(context, drugId, scheduleKey, dateStr) ||
            isMissedOn(context, drugId, scheduleKey, dateStr)

    fun missedCountOn(context: Context, dateStr: String): Int =
        getRecordSet(context, KEY_MISSED).count { it.split("|").getOrNull(2) == dateStr }

    private fun containsOccurrence(set: Set<String>, drugId: Int, scheduleKey: Int, dateStr: String): Boolean =
        set.any { it.startsWith(occurrencePrefix(drugId, scheduleKey, dateStr)) }

    private fun removeOccurrence(set: MutableSet<String>, drugId: Int, scheduleKey: Int, dateStr: String) {
        val prefix = occurrencePrefix(drugId, scheduleKey, dateStr)
        set.removeAll { it.startsWith(prefix) }
    }

    private fun occurrencePrefix(drugId: Int, scheduleKey: Int, dateStr: String) =
        "$drugId|$scheduleKey|$dateStr|"

    /* ===================== 历史迁移与查询 ===================== */

    private fun ensureRecordsMigrated(context: Context) {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.getBoolean(KEY_RECORDS_MIGRATED, false)) return

        val drugs = getAllDrugs(context).associateBy { it.id }
        val taken = preferences.getStringSet(KEY_TAKEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        val skipped = preferences.getStringSet(KEY_SKIPPED, emptySet())?.toMutableSet() ?: mutableSetOf()

        fun migrateLegacy(source: Set<String>, target: MutableSet<String>) {
            source.forEach { legacy ->
                val parts = legacy.split("|")
                val drugId = parts.getOrNull(0)?.toIntOrNull() ?: return@forEach
                val time = ReminderTime.from(parts.getOrNull(1) ?: return@forEach) ?: return@forEach
                val date = parts.getOrNull(2) ?: return@forEach
                val actual = parts.getOrNull(3) ?: time.format()
                val drug = drugs[drugId] ?: return@forEach
                val index = drug.schedules.indexOfFirst { time in it.reminderTimes }
                val schedule = drug.schedules.getOrNull(index) ?: return@forEach
                target.add(listOf(
                    drugId.toString(), schedule.scheduleKey.toString(), date, actual,
                    schedule.reminderTimes.joinToString(",") { it.format() }, schedule.displayName(index)
                ).joinToString("|"))
            }
        }

        migrateLegacy(preferences.getStringSet(KEY_LEGACY_TAKEN, emptySet()) ?: emptySet(), taken)
        migrateLegacy(preferences.getStringSet(KEY_LEGACY_IGNORED, emptySet()) ?: emptySet(), skipped)
        preferences.edit()
            .putStringSet(KEY_TAKEN, taken)
            .putStringSet(KEY_SKIPPED, skipped)
            .putBoolean(KEY_RECORDS_MIGRATED, true)
            .commit()
    }

    private fun getRecordSet(context: Context, key: String): Set<String> {
        ensureRecordsMigrated(context)
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(key, emptySet())?.toSet() ?: emptySet()
    }

    private fun editRecordSets(
        context: Context,
        block: (MutableSet<String>, MutableSet<String>, MutableSet<String>) -> Unit
    ) {
        ensureRecordsMigrated(context)
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val taken = preferences.getStringSet(KEY_TAKEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        val skipped = preferences.getStringSet(KEY_SKIPPED, emptySet())?.toMutableSet() ?: mutableSetOf()
        val missed = preferences.getStringSet(KEY_MISSED, emptySet())?.toMutableSet() ?: mutableSetOf()
        block(taken, skipped, missed)
        preferences.edit()
            .putStringSet(KEY_TAKEN, taken)
            .putStringSet(KEY_SKIPPED, skipped)
            .putStringSet(KEY_MISSED, missed)
            .commit()
    }

    fun getHistoryRecords(context: Context): List<HistoryRecord> {
        val drugs = getAllDrugs(context).associateBy { it.id }
        val result = mutableListOf<HistoryRecord>()
        getRecordSet(context, KEY_TAKEN).mapNotNullTo(result) { parseRecord(it, HistoryStatus.TAKEN, drugs) }
        getRecordSet(context, KEY_SKIPPED).mapNotNullTo(result) { parseRecord(it, HistoryStatus.SKIPPED, drugs) }
        getRecordSet(context, KEY_MISSED).mapNotNullTo(result) { parseRecord(it, HistoryStatus.MISSED, drugs) }
        return result.sortedByDescending(::recordTimestamp)
    }

    private fun parseRecord(
        value: String,
        status: HistoryStatus,
        drugs: Map<Int, Drug>
    ): HistoryRecord? {
        val parts = value.split("|")
        if (parts.size < 5) return null
        val drugId = parts[0].toIntOrNull() ?: return null
        val drug = drugs[drugId] ?: return null
        return HistoryRecord(
            drugId = drugId,
            drugName = drug.name,
            scheduleKey = parts[1].toIntOrNull() ?: return null,
            scheduleLabel = parts.getOrNull(5) ?: "一次服药",
            plannedTimes = parts[4].split(",").mapNotNull { ReminderTime.from(it) },
            dateStr = parts[2],
            actualTimeStr = parts[3],
            status = status
        )
    }

    private fun recordTimestamp(record: HistoryRecord): Long = try {
        val dateParts = record.dateStr.split("-").map(String::toInt)
        val fallback = record.plannedTimes.maxByOrNull { it.hour * 60 + it.minute } ?: ReminderTime(23, 59)
        val timeParts = if (record.actualTimeStr.startsWith("--")) {
            listOf(fallback.hour, fallback.minute, 59)
        } else {
            record.actualTimeStr.split(":").map(String::toInt).let {
                listOf(it[0], it[1], it.getOrElse(2) { 0 })
            }
        }
        Calendar.getInstance().apply {
            set(dateParts[0], dateParts[1] - 1, dateParts[2], timeParts[0], timeParts[1], timeParts[2])
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    } catch (_: Exception) {
        0L
    }

    /* ===================== 跨日核对 ===================== */

    /** 将上次核对日至昨天之间仍未确认的任务记为“已错过”，最多回溯 30 天。 */
    fun reconcilePastOccurrences(context: Context): Int {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = startOfDay(Calendar.getInstance())
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
        val start = parseDate(preferences.getString(KEY_LAST_RECONCILED_DATE, null))
            ?: (yesterday.clone() as Calendar)
        if (start.after(yesterday)) {
            preferences.edit().putString(KEY_LAST_RECONCILED_DATE, dateString(today)).commit()
            return 0
        }

        val minimum = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -30) }
        if (start.before(minimum)) start.timeInMillis = minimum.timeInMillis
        var added = 0
        val drugs = getAllDrugs(context).filter { it.enabled }
        val cursor = start.clone() as Calendar
        while (!cursor.after(yesterday)) {
            val date = dateString(cursor)
            drugs.filter { it.isScheduledOn(date) }.forEach { drug ->
                drug.schedules.forEach { schedule ->
                    if (markMissed(context, drug.id, schedule.scheduleKey, date)) added++
                }
            }
            cursor.add(Calendar.DAY_OF_MONTH, 1)
        }
        preferences.edit().putString(KEY_LAST_RECONCILED_DATE, dateString(today)).commit()
        return added
    }

    private fun parseDate(value: String?): Calendar? = try {
        if (value == null) null else startOfDay(Calendar.getInstance().apply {
            time = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(value) ?: Date()
        })
    } catch (_: Exception) {
        null
    }

    private fun startOfDay(calendar: Calendar): Calendar = calendar.apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun currentTimeString(): String {
        val now = Calendar.getInstance()
        return String.format(
            Locale.CHINA, "%02d:%02d:%02d",
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND)
        )
    }

    fun todayString(): String = dateString(Calendar.getInstance())

    fun dateString(calendar: Calendar): String = String.format(
        Locale.CHINA, "%04d-%02d-%02d",
        calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH)
    )
}
