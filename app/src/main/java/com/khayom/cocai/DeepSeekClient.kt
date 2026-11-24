package com.khayom.cocai

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.util.Locale

object DeepSeekClient {
    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-flash"
    private val allowedTabs = setOf("HOME_VILLAGE", "BUILDER_BASE", "CLAN_CAPITAL")

    fun analyzeAchievements(bitmap: Bitmap, apiKey: String): String {
        require(apiKey.isNotBlank()) { "Дар Settings API Key ворид кун." }

        // Crop toward the central achievement panel to avoid forwarding most of the
        // unrelated village HUD/player details to the vision provider.
        val landscape = bitmap.width >= bitmap.height
        val left = (bitmap.width * if (landscape) 0.13f else 0.05f).toInt()
        val top = (bitmap.height * if (landscape) 0.015f else 0.15f).toInt()
        val cropWidth = (bitmap.width * if (landscape) 0.74f else 0.90f)
            .toInt().coerceAtMost(bitmap.width - left).coerceAtLeast(1)
        val cropHeight = (bitmap.height * if (landscape) 0.97f else 0.70f)
            .toInt().coerceAtMost(bitmap.height - top).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
        val bytes = try {
            ByteArrayOutputStream().also {
                cropped.compress(Bitmap.CompressFormat.JPEG, 78, it)
            }.toByteArray()
        } finally {
            if (cropped !== bitmap && !cropped.isRecycled) cropped.recycle()
        }
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

        val prompt = """
            You are a strict visual parser for Clash of Clans Profile > Achievements.
            This app is read-only. Never suggest spending gems, buying, boosting,
            rushing, upgrading, or pressing any game button.

            ACCEPT a screenshot ONLY when you can clearly identify the in-game
            Profile > Achievements page AND one selected tab:
            - Russian "Родная деревня" => HOME_VILLAGE
            - Russian "Деревня строителя" => BUILDER_BASE
            - Russian "Столица клана" => CLAN_CAPITAL

            REJECT Events, Event Missions, Season Challenges, Clan Games, Clan War,
            shop, village, building-detail, army, settings, chat, and every other screen.
            If uncertain, set screen_type to NOT_ACHIEVEMENTS and tasks to [].
            Never invent text or numbers. Use null for unreadable values.
            Parse all visible achievement rows. The app may scan further pages later.

            Return one valid JSON object only:
            {
              "screen_type": "ACHIEVEMENTS" or "NOT_ACHIEVEMENTS",
              "tab": "HOME_VILLAGE" or "BUILDER_BASE" or "CLAN_CAPITAL" or "UNKNOWN",
              "reason": "brief explanation",
              "tasks": [
                {
                  "title": "visible title",
                  "description": "visible description",
                  "current_progress": 0,
                  "target_progress": 0,
                  "xp_reward": null,
                  "gems_reward": null,
                  "completed": false,
                  "confidence": 0.0
                }
              ]
            }
            Use integer numeric values or null. Confidence is from 0.0 to 1.0.
            Gems shown in this schema are rewards only. The app must never spend gems.
        """.trimIndent()

        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject()
                        .put("url", dataUrl)
                        .put("detail", "original"))
            )
        val message = JSONObject().put("role", "user").put("content", content)
        val request = JSONObject()
            .put("model", MODEL)
            .put("temperature", 0)
            .put("max_tokens", 3500)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray().put(message))

        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 25_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use {
                it.write(request.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val responseText = (if (code in 200..299) connection.inputStream
                else connection.errorStream)?.bufferedReader(Charsets.UTF_8)?.use {
                it.readText()
            }.orEmpty()

            if (code !in 200..299) {
                throw IllegalStateException(when (code) {
                    401, 403 -> "API Key нодуруст ё бе иҷозат аст (HTTP $code)."
                    429 -> "Лимити DeepSeek расид (HTTP 429). Баъдтар такрор кун."
                    in 500..599 -> "Сервери DeepSeek муваққатан дастнорас аст (HTTP $code)."
                    else -> "Хатои DeepSeek (HTTP $code): ${responseText.take(250)}"
                })
            }

            val raw = JSONObject(responseText)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            return validateAndFormat(raw)
        } finally {
            connection.disconnect()
        }
    }

    private fun validateAndFormat(raw: String): String {
        val cleaned = raw.trim()
            .removePrefix("'''json")
            .removePrefix("'''")
            .removeSuffix("'''")
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val json = try { JSONObject(cleaned) } catch (_: Exception) {
            throw IllegalStateException("Ҷавоби AI JSON-и дуруст нест. Скриншотро дубора таҳлил кун.")
        }

        if (json.optString("screen_type") != "ACHIEVEMENTS") {
            return "Экрани «Достижения» муайян нашуд.
" +
                    json.optString("reason", "Ин экран ба бахшҳои Achievement дохил намешавад.")
        }

        val tab = json.optString("tab")
        if (tab !in allowedTabs) {
            return "Бахши Achievement номуайян аст. Маълумот ҳамчун вазифа сабт нашуд."
        }

        val tasks = json.optJSONArray("tasks") ?: JSONArray()
        if (tasks.length() == 0) {
            return "Саҳифаи Achievement шинохта шуд, аммо сатри боэътимод хонда нашуд."
        }

        data class Task(
            val title: String, val current: Int?, val target: Int?,
            val gems: Int?, val confidence: Double, val completed: Boolean,
            val remaining: Int?, val score: Double
        )

        val parsed = mutableListOf<Task>()
        for (i in 0 until tasks.length()) {
            val item = tasks.optJSONObject(i) ?: continue
            val title = item.optString("title").trim()
            if (title.isBlank() || title == "null") continue

            val current = item.intOrNull("current_progress")
            val target = item.intOrNull("target_progress")
            val gems = item.intOrNull("gems_reward")?.coerceAtLeast(0)
            val confidence = item.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
            val completed = item.optBoolean("completed", false) ||
                (current != null && target != null && target > 0 && current >= target)
            val remaining = if (current != null && target != null && target >= current) {
                target - current
            } else null
            val progress = if (current != null && target != null && target > 0) {
                (current.toDouble() / target).coerceIn(0.0, 1.0)
            } else 0.0
            val rewardScore = ((gems ?: 0) / 300.0).coerceIn(0.0, 1.0)
            val score = (progress * 0.55 + rewardScore * 0.25 + confidence * 0.20) * 100.0
            parsed += Task(title, current, target, gems, confidence, completed, remaining, score)
        }

        val locale = Locale("ru", "RU")
        val format = NumberFormat.getIntegerInstance(locale)
        val todo = parsed.filterNot { it.completed }.sortedByDescending { it.score }
        return buildString {
            appendLine("Бахш: ${tabLabel(tab)}")
            appendLine("Вазифаҳои хондашуда: ${parsed.size}")
            appendLine("Анҷомшуда: ${parsed.count { it.completed }}")
            appendLine("Кристаллҳо танҳо мукофотанд; сарф кардани онҳо дар барнома вуҷуд надорад.")
            appendLine()
            if (todo.isEmpty()) {
                appendLine("Дар рӯйхати намоён вазифаи нотамом нест.")
            } else {
                appendLine("АВЛАВИЯҲО:")
                todo.take(20).forEachIndexed { index, task ->
                    appendLine("${index + 1}. ${task.title}")
                    if (task.current != null && task.target != null) {
                        appendLine("   Пешрафт: ${format.format(task.current)} / ${format.format(task.target)}")
                        task.remaining?.let { appendLine("   Боқимонда: ${format.format(it)}") }
                    } else {
                        appendLine("   Пешрафт: хонда нашуд")
                    }
                    task.gems?.let { appendLine("   Мукофоти кристалл: $it") }
                    appendLine("   Эътимоди хондан: ${(task.confidence * 100).toInt()}%")
                }
            }
            appendLine()
            append("Рейтинг аз пешрафт, мукофоти намоён ва эътимоди хондан сохта шуд. Вақт ва қудрати ҷангро ин скриншот танҳо муайян карда наметавонад.")
        }
    }

    private fun JSONObject.intOrNull(name: String): Int? {
        if (isNull(name)) return null
        return when (val value = opt(name)) {
            is Number -> value.toInt()
            is String -> value.replace(" ", "").replace(",", "").toIntOrNull()
            else -> null
        }
    }

    private fun tabLabel(tab: String): String = when (tab) {
        "HOME_VILLAGE" -> "Родная деревня"
        "BUILDER_BASE" -> "Деревня строителя"
        "CLAN_CAPITAL" -> "Столица клана"
        else -> "Номаълум"
    }
}
