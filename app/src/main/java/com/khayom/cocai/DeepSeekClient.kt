package com.khayom.cocai

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.text.NumberFormat
import java.util.Locale

object DeepSeekClient {
    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-flash"
    private val allowedTabs = setOf("HOME_VILLAGE", "BUILDER_BASE", "CLAN_CAPITAL")

    @Volatile private var activeConnection: HttpURLConnection? = null

    fun cancelCurrentRequest() {
        val connection = synchronized(this) {
            val current = activeConnection
            activeConnection = null
            current
        }
        try { connection?.disconnect() } catch (_: Exception) { }
    }

    fun testConnection(apiKey: String): String {
        require(apiKey.isNotBlank()) { "Аввал API Key ворид кун." }
        val connection = URL("https://api.deepseek.com/models").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                throw IllegalStateException(when (status) {
                    401, 403 -> "API Key нодуруст ё бе иҷозат аст (HTTP $status)."
                    429 -> "Лимити дархостҳои DeepSeek расид (HTTP 429)."
                    in 500..599 -> "Сервери DeepSeek муваққатан дастнорас аст (HTTP $status)."
                    else -> "Санҷиши API ноком шуд (HTTP $status)."
                })
            }

            val models = JSONObject(body).optJSONArray("data") ?: JSONArray()
            var flashFound = false
            var visionSupported = false
            for (index in 0 until models.length()) {
                val model = models.optJSONObject(index) ?: continue
                if (model.optString("id") == MODEL) {
                    flashFound = true
                    val modalities = model.optJSONArray("input_modalities") ?: JSONArray()
                    for (i in 0 until modalities.length()) {
                        if (modalities.optString(i) == "image") visionSupported = true
                    }
                    break
                }
            }
            if (!flashFound) return "Пайвастшавӣ дуруст аст, аммо модели $MODEL дар рӯйхат нест."
            if (!visionSupported) return "Модели $MODEL ёфт шуд, аммо дастгирии тасвир тасдиқ нашуд."
            return "Пайвастшавӣ муваффақ аст. API Key дуруст ва $MODEL Vision дастрас аст."
        } catch (_: UnknownHostException) {
            throw IllegalStateException("DNS сервер api.deepseek.com-ро пайдо накард. Интернет, Private DNS ё VPN-ро санҷ.")
        } catch (_: SocketTimeoutException) {
            throw IllegalStateException("Санҷиши пайвастшавӣ вақтро гузашт. Интернетро санҷ.")
        } catch (e: IOException) {
            throw IllegalStateException("Хатои шабака: ${e.message ?: "I/O error"}")
        } finally {
            connection.disconnect()
        }
    }

    fun analyzeAchievements(bitmap: Bitmap, apiKey: String, isCancelled: () -> Boolean = { false }): String {
        require(apiKey.isNotBlank()) { "Дар Settings API Key ворид кун." }
        check(!isCancelled()) { "Сессияи скан қатъ шуд." }

        // Keep the upper-left player badge visible for navigation-state detection
        // while retaining the complete central Achievement panel.
        val landscape = bitmap.width >= bitmap.height
        val left = (bitmap.width * if (landscape) 0.01f else 0.05f).toInt()
        val top = (bitmap.height * if (landscape) 0.015f else 0.15f).toInt()
        val cropWidth = (bitmap.width * if (landscape) 0.90f else 0.90f)
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
        check(!isCancelled()) { "Сессияи скан қатъ шуд." }
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
            shop, building-detail, army, settings, chat, and every other screen.
            For a non-achievement screenshot classify game_screen as:
            - VILLAGE_HOME only when the main village base and upper-left player level/profile badge are visible
            - PLAYER_PROFILE when the player profile panel is open but achievement rows are not visible
            - OTHER for every uncertain or unrelated screen
            Never request a tap, purchase, upgrade, troop deployment, or battle action.
            If uncertain, set screen_type to NOT_ACHIEVEMENTS, game_screen to OTHER and tasks to [].
            Never invent text or numbers. Use null for unreadable values.
            Parse all visible achievement rows.

            Return one valid JSON object only:
            {
              "screen_type": "ACHIEVEMENTS" or "NOT_ACHIEVEMENTS",
              "game_screen": "ACHIEVEMENTS" or "VILLAGE_HOME" or "PLAYER_PROFILE" or "OTHER",
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
            .put("reasoning_effort", "none")
            .put("max_tokens", 6000)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray().put(message))

        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            synchronized(this) {
                check(!isCancelled()) { "Сессияи скан қатъ шуд." }
                activeConnection = connection
            }
            connection.requestMethod = "POST"
            connection.connectTimeout = 25_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            check(!isCancelled()) { "Сессияи скан қатъ шуд." }
            connection.outputStream.use {
                it.write(request.toString().toByteArray(Charsets.UTF_8))
            }

            check(!isCancelled()) { "Сессияи скан қатъ шуд." }
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

            check(!isCancelled()) { "Сессияи скан қатъ шуд." }
            val raw = JSONObject(responseText)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            return validateAndFormat(raw)
        } catch (_: UnknownHostException) {
            throw IllegalStateException(
                "DNS сервер API-ро пайдо накард (api.deepseek.com). Интернет, Private DNS ё VPN-ро санҷ."
            )
        } catch (_: SocketTimeoutException) {
            throw IllegalStateException(
                "Пайвастшавӣ ба DeepSeek вақтро гузашт. Интернетро санҷ ва баъд START-ро такрор кун."
            )
        } catch (e: IOException) {
            throw IllegalStateException(
                "Хатои шабака ҳангоми пайвастшавӣ ба DeepSeek: ${e.message ?: "I/O error"}"
            )
        } finally {
            synchronized(this) {
                if (activeConnection === connection) activeConnection = null
            }
            connection.disconnect()
        }
    }

    private fun validateAndFormat(raw: String): String {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val json = try { JSONObject(cleaned) } catch (_: Exception) {
            throw IllegalStateException("Ҷавоби AI JSON-и дуруст нест. Скриншотро дубора таҳлил кун.")
        }

        if (json.optString("screen_type") != "ACHIEVEMENTS") {
            val reportedScreen = json.optString("game_screen")
            val gameScreen = if (reportedScreen == "VILLAGE_HOME" ||
                reportedScreen == "PLAYER_PROFILE") reportedScreen else "OTHER"
            return JSONObject()
                .put("screen_type", "NOT_ACHIEVEMENTS")
                .put("game_screen", gameScreen)
                .put("reason", json.optString("reason", "Ин экран Achievement нест."))
                .put("tasks", JSONArray())
                .toString()
        }

        val tab = json.optString("tab")
        if (tab !in allowedTabs) {
            return JSONObject()
                .put("screen_type", "NOT_ACHIEVEMENTS")
                .put("game_screen", "PLAYER_PROFILE")
                .put("reason", "Бахши Achievement номуайян аст.")
                .put("tasks", JSONArray())
                .toString()
        }

        val tasks = json.optJSONArray("tasks") ?: JSONArray()
        val normalized = JSONArray()
        for (i in 0 until tasks.length()) {
            val item = tasks.optJSONObject(i) ?: continue
            val title = item.optString("title").trim()
            if (title.isBlank() || title == "null") continue

            val confidence = item.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
            // Do not silently add weak visual guesses to the permanent task library.
            if (confidence < MIN_TASK_CONFIDENCE) continue

            val current = item.intOrNull("current_progress")?.takeIf { it >= 0 }
            val target = item.intOrNull("target_progress")?.takeIf { it > 0 }
            val gems = item.intOrNull("gems_reward")?.takeIf { it >= 0 }
            val xp = item.intOrNull("xp_reward")?.takeIf { it >= 0 }
            val description = item.optString("description", "").take(MAX_DESCRIPTION_LENGTH)
            val completed = item.optBoolean("completed", false) ||
                (current != null && target != null && current >= target)
            normalized.put(JSONObject()
                .put("title", title.take(MAX_TITLE_LENGTH))
                .put("description", description)
                .put("current_progress", current ?: JSONObject.NULL)
                .put("target_progress", target ?: JSONObject.NULL)
                .put("xp_reward", xp ?: JSONObject.NULL)
                .put("gems_reward", gems ?: JSONObject.NULL)
                .put("completed", completed)
                .put("confidence", confidence))
        }

        return JSONObject()
            .put("screen_type", "ACHIEVEMENTS")
            .put("game_screen", "ACHIEVEMENTS")
            .put("tab", tab)
            .put("reason", json.optString("reason", "Achievement screen recognized"))
            .put("tasks", normalized)
            .toString()
    }

    private const val MIN_TASK_CONFIDENCE = 0.50
    private const val MAX_TITLE_LENGTH = 100
    private const val MAX_DESCRIPTION_LENGTH = 240

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
