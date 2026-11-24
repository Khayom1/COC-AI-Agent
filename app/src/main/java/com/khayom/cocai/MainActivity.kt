package com.khayom.cocai

import android.app.Activity
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var keyEdit: EditText
    private var receiverRegistered = false

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            resultText.text = intent?.getStringExtra(CaptureOverlayService.EXTRA_RESULT)
                ?: "Натиҷаи нав дастрас шуд."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        resultText.text = getSharedPreferences("coc_ai_results", MODE_PRIVATE)
            .getString("last_analysis", "Ҳоло натиҷаи скан мавҷуд нест.")
        if (!receiverRegistered) {
            val filter = IntentFilter(CaptureOverlayService.ACTION_RESULT)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(resultReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    override fun onPause() {
        if (receiverRegistered) {
            unregisterReceiver(resultReceiver)
            receiverRegistered = false
        }
        super.onPause()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
            setBackgroundColor(Color.rgb(12, 21, 34))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("COC AI AGENT", 27, Color.WHITE, true))
        root.addView(text("Ёрдамчии таҳлили Achievement • Read-only", 14, Color.LTGRAY, false))
        root.addView(gap(18))

        statusText = text("", 14, Color.WHITE, false).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(29, 44, 61))
        }
        root.addView(statusText, matchWrap())
        root.addView(gap(12))

        root.addView(text("1. БОЗӢ", 16, Color.rgb(121, 190, 255), true))
        root.addView(text(
            "Тугмаи + танҳо Clash of Clans-и расмиро меёбад. Барномаҳои дигар илова намешаванд.",
            13, Color.LTGRAY, false
        ))
        root.addView(button("+  ИЛОВАИ CLASH OF CLANS") { addClashOfClans() }, matchWrap())
        root.addView(button("КУШОДАНИ CLASH OF CLANS") { openClashOfClans() }, matchWrap())
        root.addView(gap(12))

        root.addView(text("2. МОНИТОРИНГ", 16, Color.rgb(121, 190, 255), true))
        root.addView(text(
            "Сканери экран то 30 кадр/сония кор мекунад. DeepSeek танҳо ҳангоми пахши SCAN даъват мешавад, на барои ҳар кадр.",
            13, Color.LTGRAY, false
        ))
        root.addView(button("START — ОҒОЗИ СКАНЕР") { startCaptureFlow() }, matchWrap())
        root.addView(text(
            "Вақте ки бозӣ кушода аст, барои таҳлил SCAN-и тугмаи шинокунандаро пахш кун.",
            13, Color.LTGRAY, false
        ))
        root.addView(button("STOP — ҚАТЪИ ПУРРА") {
            stopService(Intent(this, CaptureOverlayService::class.java))
            toast("Сканер ва тугмаи шинокунанда қатъ шуданд.")
        }, matchWrap())
        root.addView(gap(12))

        root.addView(text("3. DEEPSEEK API KEY", 16, Color.rgb(121, 190, 255), true))
        root.addView(text(
            "Калиди шахсиро танҳо дар ҳамин ҷо ворид кун. Он бо AES-GCM ва Android Keystore нигоҳ дошта мешавад ва ба GitHub фиристода намешавад.",
            13, Color.LTGRAY, false
        ))
        keyEdit = EditText(this).apply {
            hint = "sk-..."
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setBackgroundColor(Color.rgb(29, 44, 61))
        }
        root.addView(keyEdit, matchWrap())
        root.addView(button("ЗАХИРА КАРДАНИ API KEY") {
            val value = keyEdit.text?.toString().orEmpty().trim()
            if (value.isBlank()) {
                toast("API Key холӣ аст.")
            } else {
                try {
                    SecureKeyStore.save(this, value)
                    keyEdit.setText("")
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(keyEdit.windowToken, 0)
                    toast("API Key рамзгузорӣ шуда нигоҳ дошта шуд.")
                    refreshStatus()
                } catch (e: Exception) {
                    toast("Хатои нигоҳдории калид: ${e.message}")
                }
            }
        }, matchWrap())
        root.addView(button("ҲАЗФИ API KEY") {
            SecureKeyStore.delete(this)
            keyEdit.setText("")
            toast("API Key аз дастгоҳ ҳазф шуд.")
            refreshStatus()
        }, matchWrap())
        root.addView(gap(14))

        root.addView(text("4. НАТИҶАИ ТАҲЛИЛ", 16, Color.rgb(121, 190, 255), true))
        resultText = text("Ҳоло натиҷаи скан мавҷуд нест.", 14, Color.WHITE, false).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(29, 44, 61))
        }
        root.addView(resultText, matchWrap())
        root.addView(button("ҲАЗФИ ТАЪРИХИ ACHIEVEMENT") {
            AchievementStore.clear(this)
            resultText.text = "Таърихи ҳамаи сканҳо ҳазф шуд."
            toast("Таърихи Achievement пок карда шуд.")
        }, matchWrap())
        root.addView(gap(14))

        root.addView(text(
            "ҚУЛФИ КРИСТАЛЛ: ФАЪОЛ. Барнома ба бозӣ ягон пахш ё амали харид/Upgrade намефиристад. Он танҳо экранро мехонад.",
            14, Color.rgb(132, 235, 170), true
        ))
        root.addView(gap(10))
        root.addView(text(
            "Барои гирифтани маълумоти сатҳи бино ё сарбоз, худат онро дар бозӣ кушо. AI худкор ба объект намезанад ва тугмаҳои бозиро пахш намекунад.",
            12, Color.LTGRAY, false
        ))
    }

    private fun addClashOfClans() {
        toast(if (isClashInstalled()) "Clash of Clans илова шуд."
        else "Clash of Clans-и расмӣ дар телефон насб нест.")
        refreshStatus()
    }

    private fun openClashOfClans() {
        if (!isClashInstalled()) {
            toast("Аввал Clash of Clans-ро насб кун.")
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(CaptureOverlayService.ALLOWED_PACKAGE)
        if (launch == null) toast("Бозӣ кушода нашуд.") else startActivity(launch)
    }

    private fun isClashInstalled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(
                CaptureOverlayService.ALLOWED_PACKAGE,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(CaptureOverlayService.ALLOWED_PACKAGE, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun startCaptureFlow() {
        if (!isClashInstalled()) {
            toast("Clash of Clans насб нест.")
            return
        }
        if (!hasUsageAccess()) {
            toast("Usage Access-ро фаъол кун, то барнома танҳо ҳангоми кушода будани Clash скан кунад.")
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Барои тугмаи шинокунанда иҷозати Display over other apps лозим аст.")
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    @Deprecated("MediaProjection requires the system consent result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            toast("Иҷозати сабти экран дода нашуд.")
            return
        }
        val service = Intent(this, CaptureOverlayService::class.java).apply {
            action = CaptureOverlayService.ACTION_START_CAPTURE
            putExtra(CaptureOverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureOverlayService.EXTRA_PROJECTION_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_START_FROM_OVERLAY) startCaptureFlow()
    }

    private fun refreshStatus() {
        val installed = isClashInstalled()
        val usage = hasUsageAccess()
        val overlay = Settings.canDrawOverlays(this)
        val key = SecureKeyStore.hasKey(this)
        statusText.text = buildString {
            appendLine("Clash of Clans: ${if (installed) "илова шудааст ✓" else "ёфт нашуд"}")
            appendLine("Usage Access: ${if (usage) "фаъол ✓" else "лозим аст"}")
            appendLine("Floating overlay: ${if (overlay) "фаъол ✓" else "лозим аст"}")
            append("DeepSeek API Key: ${if (key) "захира шудааст ✓" else "ворид нашудааст"}")
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(5))
        }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setOnClickListener { action() }
        }

    private fun gap(heightDp: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(7) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val REQUEST_CAPTURE = 4001
        const val ACTION_START_FROM_OVERLAY = "com.khayom.cocai.START_FROM_OVERLAY"
    }
}
