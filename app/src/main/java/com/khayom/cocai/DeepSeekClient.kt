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
        synchronized(this) { activeConnection = connection }
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
            return JSONObject()
                .put("screen_type", "NOT_ACHIEVEMENTS")
                .put("reason", json.optString("reason", "Ин экран Achievement нест."))
                .put("tasks", JSONArray())
                .toString()
        }

        val tab = json.optString("tab")
        if (tab !in allowedTabs) {
            return JSONObject()
                .put("screen_type", "NOT_ACHIEVEMENTS")
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

            val current = item.intOrNull("current_progress")
            val target = item.intOrNull("target_progress")
            val gems = item.intOrNull("gems_reward")?.coerceAtLeast(0)
            val completed = item.optBoolean("completed", false) ||
                (current != null && target != null && target > 0 && current >= target)
            normalized.put(JSONObject()
                .put("title", title)
                .put("description", item.optString("description", ""))
                .put("current_progress", current ?: JSONObject.NULL)
                .put("target_progress", target ?: JSONObject.NULL)
                .put("xp_reward", item.intOrNull("xp_reward") ?: JSONObject.NULL)
                .put("gems_reward", gems ?: JSONObject.NULL)
                .put("completed", completed)
                .put("confidence", item.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)))
        }

        return JSONObject()
            .put("screen_type", "ACHIEVEMENTS")
            .put("tab", tab)
            .put("reason", json.optString("reason", "Achievement screen recognized"))
            .put("tasks", normalized)
            .toString()
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
