package com.khayom.cocai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/**
 * Merges repeated Achievement scans from different scroll positions and tabs.
 * Only model-validated Achievement JSON is persisted.
 */
object AchievementStore {
    private const val PREFS = "coc_ai_results"
    private const val DATABASE_KEY = "achievements_database"
    private val allowedTabs = setOf("HOME_VILLAGE", "BUILDER_BASE", "CLAN_CAPITAL")

    fun mergeAndFormat(context: Context, scanPayload: String): String {
        val scan = try {
            JSONObject(scanPayload)
        } catch (_: Exception) {
            return "Ҷавоби AI сохтори дуруст надошт; ба база илова карда нашуд."
        }

        if (scan.optString("screen_type") != "ACHIEVEMENTS") {
            return "Ин экран Achievement нест — ба база чизе илова нашуд. " +
                scan.optString("reason", "")
        }

        val tab = scan.optString("tab")
        if (tab !in allowedTabs) {
            return "Бахши Achievement тасдиқ нашуд; маълумот сабт карда нашуд."
        }

        val incoming = scan.optJSONArray("tasks") ?: JSONArray()
        if (incoming.length() == 0) {
            return "Саҳифаи Achievement шинохта шуд, вале ягон сатр боэътимод хонда нашуд."
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val database = try {
            JSONObject(prefs.getString(DATABASE_KEY, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val all = database.optJSONArray("tasks") ?: JSONArray()
        val merged = linkedMapOf<String, JSONObject>()

        for (i in 0 until all.length()) {
            val task = all.optJSONObject(i) ?: continue
            val existingTab = task.optString("tab")
            val title = task.optString("title")
            if (existingTab in allowedTabs && title.isNotBlank()) {
                merged[key(existingTab, title)] = task
            }
        }

        val now = System.currentTimeMillis()
        var accepted = 0
        for (i in 0 until incoming.length()) {
            val fresh = incoming.optJSONObject(i) ?: continue
            val title = fresh.optString("title").trim()
            if (title.isBlank() || title == "null") continue
            val id = key(tab, title)
            val old = merged[id]

            if (old == null) {
                val observations = JSONArray()
                fresh.intOrNull("current_progress")?.let { progress ->
                    observations.put(JSONObject().put("at", now).put("progress", progress))
                }
                merged[id] = JSONObject(fresh.toString())
                    .put("tab", tab)
                    .put("observations", observations)
                    .put("first_seen_at", now)
                    .put("last_seen_at", now)
                accepted++
            } else {
                val oldCurrent = old.intOrNull("current_progress")
                val newCurrent = fresh.intOrNull("current_progress")
                val oldTarget = old.intOrNull("target_progress")
                val newTarget = fresh.intOrNull("target_progress")
                val current = when {
                    oldCurrent == null -> newCurrent
                    newCurrent == null -> oldCurrent
                    else -> maxOf(oldCurrent, newCurrent)
                }
                val target = newTarget ?: oldTarget
                val oldConfidence = old.optDouble("confidence", 0.0)
                val newConfidence = fresh.optDouble("confidence", 0.0)
                val completed = old.optBoolean("completed") ||
                    fresh.optBoolean("completed") ||
                    (current != null && target != null && target > 0 && current >= target)

                old.put("current_progress", current ?: JSONObject.NULL)
                    .put("target_progress", target ?: JSONObject.NULL)
                    .put("completed", completed)
                    .put("confidence", maxOf(oldConfidence, newConfidence))
                    .put("last_seen_at", now)

                val observations = old.optJSONArray("observations") ?: JSONArray()
                val lastObservation = observations.optJSONObject(observations.length() - 1)
                val lastObservedAt = lastObservation?.optLong("at", 0L) ?: 0L
                val lastObservedProgress = lastObservation?.optInt("progress", -1) ?: -1
                if (current != null && current > lastObservedProgress &&
                    (lastObservedAt == 0L || now - lastObservedAt >= MIN_OBSERVATION_INTERVAL_MS)) {
                    observations.put(JSONObject().put("at", now).put("progress", current))
                    while (observations.length() > MAX_OBSERVATIONS) {
                        val trimmed = JSONArray()
                        for (index in 1 until observations.length()) {
                            trimmed.put(observations.get(index))
                        }
                        while (observations.length() > 0) observations.remove(0)
                        for (index in 0 until trimmed.length()) observations.put(trimmed.get(index))
                    }
                }
                old.put("observations", observations)

                if (newConfidence >= oldConfidence) {
                    val newDescription = fresh.optString("description", "").trim()
                    if (newDescription.isNotEmpty()) old.put("description", newDescription)
                    if (!fresh.isNull("gems_reward")) old.put("gems_reward", fresh.opt("gems_reward"))
                    if (!fresh.isNull("xp_reward")) old.put("xp_reward", fresh.opt("xp_reward"))
                }
                merged[id] = old
                accepted++
            }
        }

        val outputTasks = JSONArray()
        merged.values.forEach { outputTasks.put(it) }
        val outDb = JSONObject()
            .put("schema_version", 1)
            .put("updated_at", now)
            .put("tasks", outputTasks)
        prefs.edit().putString(DATABASE_KEY, outDb.toString()).apply()

        return format(merged.values.toList(), tab, accepted)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(DATABASE_KEY).remove("last_analysis").apply()
    }

    fun totalCount(context: Context): Int {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(DATABASE_KEY, null) ?: return 0
        return try { JSONObject(raw).optJSONArray("tasks")?.length() ?: 0 }
        catch (_: Exception) { 0 }
    }

    private fun format(tasks: List<JSONObject>, lastTab: String, scannedCount: Int): String {
        data class Ranked(
            val item: JSONObject,
            val completed: Boolean,
            val score: Double,
            val remaining: Int?,
            val etaHours: Double?
        )

        val parsed = tasks.map { item ->
            val current = item.intOrNull("current_progress")
            val target = item.intOrNull("target_progress")
            val gems = (item.intOrNull("gems_reward") ?: 0).coerceAtLeast(0)
            val confidence = item.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
            val completed = item.optBoolean("completed") ||
                (current != null && target != null && target > 0 && current >= target)
            val ratio = if (current != null && target != null && target > 0) {
                (current.toDouble() / target).coerceIn(0.0, 1.0)
            } else 0.0
            val rewardScore = (gems / 300.0).coerceIn(0.0, 1.0)
            val remaining = if (current != null && target != null && target >= current) {
                target - current
            } else null
            val eta = estimateHours(item, remaining)
            val speedScore = eta?.let { (1.0 / (1.0 + it / 24.0)).coerceIn(0.0, 1.0) }
            val score = if (speedScore != null) {
                ratio * 0.35 + rewardScore * 0.25 + confidence * 0.15 + speedScore * 0.25
            } else {
                ratio * 0.55 + rewardScore * 0.25 + confidence * 0.20
            }
            Ranked(item, completed, score, remaining, eta)
        }

        val unfinished = parsed.filterNot { it.completed }.sortedByDescending { it.score }
        val completedCount = parsed.count { it.completed }
        val fmt = NumberFormat.getIntegerInstance(Locale("ru", "RU"))

        return buildString {
            appendLine("База Achievement — ҳамаи сканҳои ҷамъшуда")
            appendLine("Сканкунии охирин: ${tabLabel(lastTab)}")
            appendLine("Вазифаҳои нодир: ${parsed.size}")
            appendLine("Анҷомшуда: $completedCount")
            appendLine("Сатри ин скан қабул/навсозӣ шуд: $scannedCount")
            appendLine("ҚУЛФИ КРИСТАЛЛ: фаъол — танҳо мукофот ҳисоб мешавад, харҷ нест.")
            appendLine()
            appendLine("АВЛАВИЯҲО:")
            if (unfinished.isEmpty()) {
                appendLine("Ҳамаи вазифаҳои ҷамъшуда анҷом шудаанд ё маълумоти кофӣ надоранд.")
            } else {
                unfinished.take(30).forEachIndexed { index, ranked ->
                    val item = ranked.item
                    appendLine("${index + 1}. [${tabLabel(item.optString("tab"))}] ${item.optString("title")}")
                    val current = item.intOrNull("current_progress")
                    val target = item.intOrNull("target_progress")
                    if (current != null && target != null) {
                        appendLine("   Пешрафт: ${fmt.format(current)} / ${fmt.format(target)}")
                        ranked.remaining?.let { appendLine("   Боқимонда: ${fmt.format(it)}") }
                    } else {
                        appendLine("   Пешрафт: хонда нашуд")
                    }
                    item.intOrNull("gems_reward")?.let { appendLine("   Мукофоти кристалл: $it") }
                    ranked.etaHours?.let { appendLine("   Вақти тахминӣ: ${formatDuration(it)}") }
                        ?: appendLine("   Вақти анҷом: ҳоло маълум нест")
                    appendLine("   Эътимоди хондан: ${(item.optDouble("confidence", 0.0) * 100).toInt()}%")
                }
            }
            appendLine()
            append("Рейтинг тахминӣ аст: пешрафт, кристаллҳои мукофотӣ ва эътимоди OCR/Vision-ро ба назар мегирад. Барои пур кардани база, рӯйхатро дастӣ ба поён гардон ва SCAN-ро такрор кун.")
        }
    }

    private fun estimateHours(item: JSONObject, remaining: Int?): Double? {
        if (remaining == null || remaining <= 0) return null
        val observations = item.optJSONArray("observations") ?: return null
        if (observations.length() < 2) return null
        val first = observations.optJSONObject(0) ?: return null
        val last = observations.optJSONObject(observations.length() - 1) ?: return null
        val elapsedMs = last.optLong("at") - first.optLong("at")
        val progressDelta = last.optInt("progress") - first.optInt("progress")
        if (elapsedMs < MIN_OBSERVATION_INTERVAL_MS || progressDelta <= 0) return null
        val elapsedHours = elapsedMs / 3_600_000.0
        val ratePerHour = progressDelta / elapsedHours
        if (ratePerHour <= 0.0) return null
        return remaining / ratePerHour
    }

    private fun formatDuration(hours: Double): String {
        val minutes = (hours * 60.0).toLong().coerceAtLeast(1)
        return when {
            minutes < 60 -> "$minutes дақиқа"
            minutes < 24 * 60 -> "${minutes / 60} соату ${minutes % 60} дақиқа"
            else -> "${minutes / (24 * 60)} рӯз"
        }
    }

    private fun key(tab: String, title: String): String =
        tab + "|" + title.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun tabLabel(tab: String): String = when (tab) {
        "HOME_VILLAGE" -> "Родная деревня"
        "BUILDER_BASE" -> "Деревня строителя"
        "CLAN_CAPITAL" -> "Столица клана"
        else -> "Номаълум"
    }

    private const val MIN_OBSERVATION_INTERVAL_MS = 60_000L
    private const val MAX_OBSERVATIONS = 8

    private fun JSONObject.intOrNull(name: String): Int? {
        if (isNull(name)) return null
        return when (val value = opt(name)) {
            is Number -> value.toInt()
            is String -> value.replace(" ", "").replace(",", "").toIntOrNull()
            else -> null
        }
    }
}
